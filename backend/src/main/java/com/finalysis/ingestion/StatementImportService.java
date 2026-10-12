package com.finalysis.ingestion;

import com.finalysis.account.Account;
import com.finalysis.account.AccountRepository;
import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import com.finalysis.api.MoneyJson;
import com.finalysis.api.NotFoundException;
import com.finalysis.ingestion.Reconciliation.RequestBalances;
import com.finalysis.ingestion.StatementParserRegistry.AmbiguousFormatException;
import com.finalysis.ingestion.StatementParserRegistry.UnsupportedFormatException;
import com.finalysis.ingestion.csv.CsvMappingMismatchException;
import com.finalysis.ingestion.csv.CsvMappingRequiredException;
import com.finalysis.ingestion.csv.CsvMappingService;
import com.finalysis.ingestion.csv.CsvMappingSpec;
import com.finalysis.ingestion.csv.CsvPreview;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports statement files (ING-01, ING-03, ING-04) and previews them. Checks, detection and parsing
 * run outside any database transaction; {@link StatementImportWriter} then saves the result in one
 * transaction. A parser that {@linkplain StatementParser#usesCsvMapping() uses the CSV mapping} gets
 * a snapshot of it, read in its own transaction before parsing; the writer checks under the account
 * lock that it is still current. Logs carry ids, counts and statuses only, never file contents,
 * names or account numbers (SEC-10).
 */
@Service
public class StatementImportService {

    private static final Logger log = LoggerFactory.getLogger(StatementImportService.class);
    private static final String SUPPORTED_CURRENCY = "USD";
    private static final InvalidField PERIOD_END_BEFORE_START = new InvalidField("periodEnd", "must not be before periodStart");

    private final AccountRepository accounts;
    private final StatementRepository statements;
    private final TxnRepository txns;
    private final StatementParserRegistry parsers;
    private final StatementImportWriter writer;
    private final CsvMappingService csvMappings;

    public StatementImportService(AccountRepository accounts, StatementRepository statements, TxnRepository txns,
                                  StatementParserRegistry parsers, StatementImportWriter writer,
                                  CsvMappingService csvMappings) {
        this.accounts = accounts;
        this.statements = statements;
        this.txns = txns;
        this.parsers = parsers;
        this.writer = writer;
        this.csvMappings = csvMappings;
    }

    public ImportSummary importStatement(ImportRequest request) {
        Account account = account(request.accountId());
        ImportFile file = checkedFile(request.fileName(), request.bytes());
        statements.findByFileHash(file.sha256()).ifPresent(existing -> {
            throw ImportRejectedException.alreadyImported(existing.getId());
        });
        checkRequestPeriod(request);

        StatementParser parser = select(file);
        CsvMappingSpec csvMapping = parser.usesCsvMapping() ? csvMappings.snapshot(account.getId()) : null;
        ParsedStatement parsed = parse(parser, file,
                new ParseContext(account.getType(), csvMapping, request.periodStart(), request.periodEnd()), account);
        checkHints(parsed.hints(), account);

        RequestBalances balances = checkRequestValues(parsed, request);
        ImportSummary summary = writer.write(account.getId(), file, parser.id(), withRequestPeriod(parsed, request),
                balances, csvMapping);
        log.info("Imported statement {} for account {} with parser {}: {} parsed, {} inserted, {} duplicates, {}",
                summary.statementId(), account.getId(), summary.parser(), summary.parsed(), summary.inserted(),
                summary.duplicates(), summary.status());
        return summary;
    }

    /**
     * Reads a file as an import would, with the same upload checks, and writes nothing. CSV files
     * get their layout, first rows, mapping status and, unless the saved mapping matches, a
     * suggested mapping; other formats get their period and transaction count. The preview holds
     * file contents, so only its format is logged.
     */
    public ImportPreview preview(long accountId, String fileName, byte[] bytes) {
        Account account = account(accountId);
        ImportFile file = checkedFile(fileName, bytes);
        StatementParser parser = select(file);
        ImportPreview preview;
        if (parser.usesCsvMapping()) {
            preview = csvPreview(file, account, csvMappings.snapshot(accountId));
        } else {
            ParsedStatement parsed = parse(parser, file, new ParseContext(account.getType()), account);
            checkHints(parsed.hints(), account);
            preview = ImportPreview.statement(parser.id(), parsed);
        }
        log.info("Previewed a {} file for account {}", preview.format(), accountId);
        return preview;
    }

    /** The account's statements, newest period first, optionally only those with {@code status}. */
    @Transactional(readOnly = true)
    public List<StatementListItem> list(long accountId, StatementStatus status) {
        if (!accounts.existsById(accountId)) {
            throw new NotFoundException("Account", accountId);
        }
        Map<Long, Long> owned = new HashMap<>();
        for (Object[] row : txns.countByStatementForAccount(accountId)) {
            owned.put((Long) row[0], (Long) row[1]);
        }
        return statements.findForAccount(accountId, status).stream()
                .map(statement -> StatementListItem.from(statement, owned.getOrDefault(statement.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public StatementResponse get(long id) {
        Statement statement = statements.findById(id).orElseThrow(() -> new NotFoundException("Statement", id));
        return StatementResponse.from(statement, txns.countByStatementId(id));
    }

    private Account account(long accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new NotFoundException("Account", accountId));
    }

    /** Upload checks (SEC-10), in order: size, name, content. */
    private static ImportFile checkedFile(String originalFileName, byte[] bytes) {
        UploadCheck.checkSize(bytes.length);
        String fileName = UploadCheck.cleanFileName(originalFileName);
        UploadCheck.checkFileName(fileName);
        UploadCheck.checkContent(bytes);
        return ImportFile.of(fileName, bytes);
    }

    private ParsedStatement parse(StatementParser parser, ImportFile file, ParseContext context, Account account) {
        try {
            return parser.parse(file, context);
        } catch (StatementParseException e) {
            throw ImportRejectedException.parseError(e);
        } catch (CsvMappingRequiredException e) {
            throw ImportRejectedException.mappingRequired(account.getId());
        } catch (CsvMappingMismatchException e) {
            throw ImportRejectedException.mappingMismatch(account.getId(),
                    csvPreview(file, account, context.csvMapping()).withoutRows());
        }
    }

    private static ImportPreview csvPreview(ImportFile file, Account account, CsvMappingSpec saved) {
        try {
            return ImportPreview.csv(CsvPreview.of(file, account.getType(), saved));
        } catch (StatementParseException e) {
            throw ImportRejectedException.parseError(e);
        }
    }

    private StatementParser select(ImportFile file) {
        try {
            return parsers.select(file);
        } catch (UnsupportedFormatException e) {
            throw new ImportRejectedException(ImportProblem.UNSUPPORTED_FORMAT,
                    "The file is not in a supported statement format (OFX, QFX, CSV)");
        } catch (AmbiguousFormatException e) {
            log.error("Statement parsers {} all claim the same file", e.getParserIds());
            throw new ImportRejectedException(ImportProblem.AMBIGUOUS_FORMAT,
                    "More than one statement format matches this file");
        }
    }

    /** Currency first, then the account. Hints a format doesn't carry are not checked. */
    private static void checkHints(AccountHints hints, Account account) {
        if (hints.currency() != null && !hints.currency().equals(SUPPORTED_CURRENCY)) {
            throw new ImportRejectedException(ImportProblem.UNSUPPORTED_CURRENCY,
                    "Only USD statements are supported; this file is in " + hints.currency());
        }
        if (hints.type() != null && hints.type() != account.getType()) {
            throw new ImportRejectedException(ImportProblem.ACCOUNT_MISMATCH,
                    "The file is a " + hints.type() + " statement, but account " + account.getId() + " is " + account.getType());
        }
        if (hints.lastFour() != null && !hints.lastFour().equals(account.getLastFour())) {
            throw new ImportRejectedException(ImportProblem.ACCOUNT_MISMATCH,
                    "The file's account number does not end in account " + account.getId() + "'s last four digits");
        }
    }

    /**
     * A parser may read the request's period (a CSV file without rows), so a period whose two ends
     * are inverted is refused before parsing, with every other request error. Everything else,
     * including a single end checked against the file's period, is checked in
     * {@link #checkRequestValues}.
     */
    private static void checkRequestPeriod(ImportRequest request) {
        if (request.periodStart() != null && request.periodEnd() != null
                && request.periodEnd().isBefore(request.periodStart())) {
            List<InvalidField> errors = new ArrayList<>();
            validMoney(request.openingBalance(), "openingBalance", errors);
            validMoney(request.closingBalance(), "closingBalance", errors);
            errors.add(PERIOD_END_BEFORE_START);
            throw new InvalidRequestException(errors);
        }
    }

    /**
     * Checks every request value, reporting all errors at once: the balances, and the period
     * against the file's when only one end is given.
     */
    private static RequestBalances checkRequestValues(ParsedStatement parsed, ImportRequest request) {
        List<InvalidField> errors = new ArrayList<>();
        BigDecimal opening = validMoney(request.openingBalance(), "openingBalance", errors);
        BigDecimal closing = validMoney(request.closingBalance(), "closingBalance", errors);
        LocalDate start = request.periodStart() != null ? request.periodStart() : parsed.periodStart();
        LocalDate end = request.periodEnd() != null ? request.periodEnd() : parsed.periodEnd();
        if (end.isBefore(start)) {
            errors.add(PERIOD_END_BEFORE_START);
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
        return new RequestBalances(opening, closing);
    }

    /**
     * The parsed statement with the request's period applied. The request's balances are not
     * merged here: reconciliation compares them with the file's ({@link Reconciliation}).
     */
    private static ParsedStatement withRequestPeriod(ParsedStatement parsed, ImportRequest request) {
        LocalDate start = request.periodStart() != null ? request.periodStart() : parsed.periodStart();
        LocalDate end = request.periodEnd() != null ? request.periodEnd() : parsed.periodEnd();
        return new ParsedStatement(parsed.hints(), start, end, parsed.openingBalance(), parsed.closingBalance(),
                parsed.sectionTotals(), parsed.txns(), parsed.warnings());
    }

    private static BigDecimal validMoney(String text, String field, List<InvalidField> errors) {
        if (text == null) {
            return null;
        }
        try {
            return MoneyJson.parse(text);
        } catch (IllegalArgumentException e) {
            errors.add(new InvalidField(field, MoneyJson.EXPECTED));
            return null;
        }
    }

}

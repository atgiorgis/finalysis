package com.finalysis.ingestion;

import com.finalysis.account.Account;
import com.finalysis.account.AccountRepository;
import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import com.finalysis.api.NotFoundException;
import com.finalysis.ingestion.StatementParserRegistry.AmbiguousFormatException;
import com.finalysis.ingestion.StatementParserRegistry.UnsupportedFormatException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports statement files (ING-01, ING-03, ING-04). Checks, detection and parsing run outside any
 * database transaction; {@link StatementImportWriter} then saves the result in one transaction.
 * Logs carry ids, counts and statuses only, never file contents, names or account numbers (SEC-10).
 */
@Service
public class StatementImportService {

    private static final Logger log = LoggerFactory.getLogger(StatementImportService.class);
    private static final String SUPPORTED_CURRENCY = "USD";

    private final AccountRepository accounts;
    private final StatementRepository statements;
    private final TxnRepository txns;
    private final StatementParserRegistry parsers;
    private final StatementImportWriter writer;

    public StatementImportService(AccountRepository accounts, StatementRepository statements, TxnRepository txns,
                                  StatementParserRegistry parsers, StatementImportWriter writer) {
        this.accounts = accounts;
        this.statements = statements;
        this.txns = txns;
        this.parsers = parsers;
        this.writer = writer;
    }

    public ImportSummary importStatement(ImportRequest request) {
        Account account = accounts.findById(request.accountId())
                .orElseThrow(() -> new NotFoundException("Account", request.accountId()));
        UploadCheck.checkSize(request.bytes().length);
        String fileName = UploadCheck.cleanFileName(request.fileName());
        UploadCheck.checkFileName(fileName);
        UploadCheck.checkContent(request.bytes());

        ImportFile file = ImportFile.of(fileName, request.bytes());
        statements.findByFileHash(file.sha256()).ifPresent(existing -> {
            throw ImportRejectedException.alreadyImported(existing.getId());
        });

        StatementParser parser = select(file);
        ParsedStatement parsed;
        try {
            parsed = parser.parse(file, new ParseContext(account.getType()));
        } catch (StatementParseException e) {
            throw ImportRejectedException.parseError(e);
        }
        checkHints(parsed.hints(), account);

        ImportSummary summary = writer.write(account.getId(), file, parser.id(), withRequestValues(parsed, request));
        log.info("Imported statement {} for account {} with parser {}: {} parsed, {} inserted, {} duplicates, {}",
                summary.statementId(), account.getId(), summary.parser(), summary.parsed(), summary.inserted(),
                summary.duplicates(), summary.status());
        return summary;
    }

    @Transactional(readOnly = true)
    public StatementResponse get(long id) {
        Statement statement = statements.findById(id).orElseThrow(() -> new NotFoundException("Statement", id));
        return StatementResponse.from(statement, txns.countByStatementId(id));
    }

    private StatementParser select(ImportFile file) {
        try {
            return parsers.select(file);
        } catch (UnsupportedFormatException e) {
            throw new ImportRejectedException(ImportProblem.UNSUPPORTED_FORMAT,
                    "The file is not in a supported statement format (OFX, QFX)");
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

    /** Applies the request's period and fills balances the file doesn't print; conflicts keep the file's value. */
    private static ParsedStatement withRequestValues(ParsedStatement parsed, ImportRequest request) {
        List<InvalidField> errors = new ArrayList<>();
        BigDecimal opening = validMoney(request.openingBalance(), "openingBalance", errors);
        BigDecimal closing = validMoney(request.closingBalance(), "closingBalance", errors);
        LocalDate start = request.periodStart() != null ? request.periodStart() : parsed.periodStart();
        LocalDate end = request.periodEnd() != null ? request.periodEnd() : parsed.periodEnd();
        if (end.isBefore(start)) {
            errors.add(new InvalidField("periodEnd", "must not be before periodStart"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }

        List<String> warnings = new ArrayList<>(parsed.warnings());
        BigDecimal mergedOpening = merge(parsed.openingBalance(), opening, "openingBalance", warnings);
        BigDecimal mergedClosing = merge(parsed.closingBalance(), closing, "closingBalance", warnings);
        return new ParsedStatement(parsed.hints(), start, end, mergedOpening, mergedClosing, parsed.sectionTotals(),
                parsed.txns(), warnings);
    }

    private static BigDecimal validMoney(BigDecimal value, String field, List<InvalidField> errors) {
        if (value == null) {
            return null;
        }
        try {
            return Money.requireNumeric12x2(value, field);
        } catch (IllegalArgumentException e) {
            errors.add(new InvalidField(field, "must have at most two decimal places and ten digits before the point"));
            return null;
        }
    }

    private static BigDecimal merge(BigDecimal fromFile, BigDecimal fromRequest, String field, List<String> warnings) {
        if (fromFile == null) {
            return fromRequest;
        }
        if (fromRequest != null && fromRequest.compareTo(fromFile) != 0) {
            warnings.add(field + " in the request differs from the file; the file's value was kept");
        }
        return fromFile;
    }
}

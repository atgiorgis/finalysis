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
import com.finalysis.ingestion.pdf.PdfDocumentText;
import com.finalysis.ingestion.pdf.PdfExtractionException;
import com.finalysis.ingestion.pdf.PdfStatementParser;
import com.finalysis.ingestion.pdf.PdfTextExtractor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
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
 *
 * <p>Files are routed by {@linkplain FileKind kind} (docs/plans/phase-3b.md §1.6). A PDF is
 * extracted once, so its encryption check and limits run before any layout code, and only PDF
 * layout parsers see it. A PDF none of them recognizes is not a statement: it is recorded as an
 * {@link UnparsedUpload} (ING-13) and the import answers 202 Accepted. A re-upload of it runs
 * detection again, and a successful import resolves it.
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
    private final PdfTextExtractor pdfExtractor;

    public StatementImportService(AccountRepository accounts, StatementRepository statements, TxnRepository txns,
                                  StatementParserRegistry parsers, StatementImportWriter writer,
                                  CsvMappingService csvMappings, PdfTextExtractor pdfExtractor) {
        this.accounts = accounts;
        this.statements = statements;
        this.txns = txns;
        this.parsers = parsers;
        this.writer = writer;
        this.csvMappings = csvMappings;
        this.pdfExtractor = pdfExtractor;
    }

    public ImportOutcome importStatement(ImportRequest request) {
        Account account = account(request.accountId());
        CheckedFile checked = checkedFile(request.fileName(), request.bytes());
        ImportFile file = checked.file();
        statements.findByFileHash(file.sha256()).ifPresent(existing -> {
            throw ImportRejectedException.alreadyImported(existing.getId());
        });
        checkRequestPeriod(request);

        StatementParser parser;
        CsvMappingSpec csvMapping = null;
        ParsedStatement parsed;
        if (checked.kind() == FileKind.PDF) {
            PdfDocumentText text = extract(file);
            Optional<PdfStatementParser> pdfParser = selectPdf(text);
            if (pdfParser.isEmpty()) {
                return new ImportOutcome.Unparsed(recordUnparsed(account, file, text, request));
            }
            parser = pdfParser.get();
            ParseContext context = new ParseContext(account.getType(), null, request.periodStart(), request.periodEnd());
            parsed = parse(() -> pdfParser.get().parse(text, context), file, context, account);
        } else {
            parser = select(file);
            csvMapping = parser.usesCsvMapping() ? csvMappings.snapshot(account.getId()) : null;
            ParseContext context = new ParseContext(account.getType(), csvMapping, request.periodStart(),
                    request.periodEnd());
            StatementParser textParser = parser;
            parsed = parse(() -> textParser.parse(file, context), file, context, account);
        }
        checkHints(parsed.hints(), account);

        RequestBalances balances = checkRequestValues(parsed, request);
        ImportSummary summary = writer.write(account.getId(), file, parser.id(), withRequestPeriod(parsed, request),
                balances, csvMapping);
        log.info("Imported statement {} for account {} with parser {}: {} parsed, {} inserted, {} duplicates, {}",
                summary.statementId(), account.getId(), summary.parser(), summary.parsed(), summary.inserted(),
                summary.duplicates(), summary.status());
        return new ImportOutcome.Imported(summary);
    }

    /**
     * Records (or finds) the unparsed upload. The request's balances are still validated, so a
     * malformed request is refused the same way whatever the file holds; its period and balances
     * are otherwise unused, since nothing is imported.
     */
    private UnparsedUploadResponse recordUnparsed(Account account, ImportFile file, PdfDocumentText text,
                                                  ImportRequest request) {
        List<InvalidField> errors = new ArrayList<>();
        validMoney(request.openingBalance(), "openingBalance", errors);
        validMoney(request.closingBalance(), "closingBalance", errors);
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
        UnparsedUploadResponse upload = writer.recordUnparsed(account.getId(), file, UnparsedReason.of(text));
        log.info("No PDF layout recognized for account {}: unparsed upload {} ({})", account.getId(), upload.id(),
                upload.reason());
        return upload;
    }

    /**
     * Reads a file as an import would, with the same upload checks, and writes nothing. CSV files
     * get their layout, first rows, mapping status and, unless the saved mapping matches, a
     * suggested mapping; other formats get their period and transaction count. A PDF no layout
     * parser recognizes gets {@code recognized: false} and the reason, and no unparsed upload is
     * recorded. The preview holds file contents, so only its format is logged.
     */
    public ImportPreview preview(long accountId, String fileName, byte[] bytes) {
        Account account = account(accountId);
        CheckedFile checked = checkedFile(fileName, bytes);
        ImportFile file = checked.file();
        ParseContext context = new ParseContext(account.getType());
        ImportPreview preview;
        if (checked.kind() == FileKind.PDF) {
            PdfDocumentText text = extract(file);
            Optional<PdfStatementParser> parser = selectPdf(text);
            if (parser.isEmpty()) {
                preview = ImportPreview.unrecognizedPdf(UnparsedReason.of(text));
            } else {
                ParsedStatement parsed = parse(() -> parser.get().parse(text, context), file, context, account);
                checkHints(parsed.hints(), account);
                preview = ImportPreview.statement(parser.get().id(), parsed);
            }
        } else {
            StatementParser parser = select(file);
            if (parser.usesCsvMapping()) {
                preview = csvPreview(file, account, csvMappings.snapshot(accountId));
            } else {
                ParsedStatement parsed = parse(() -> parser.parse(file, context), file, context, account);
                checkHints(parsed.hints(), account);
                preview = ImportPreview.statement(parser.id(), parsed);
            }
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

    /** Marks a NEEDS_REVIEW statement reviewed; status and warnings are kept (ING-07). */
    public StatementResponse review(long id) {
        return writer.review(id);
    }

    /** Deletes a statement with its rows; transfer detection re-runs after commit (ING-07). */
    public StatementDeleteResponse delete(long id) {
        StatementDeleteResponse deleted = writer.delete(id);
        log.info("Deleted statement {}: {} transactions, {} transfer links", deleted.statementId(),
                deleted.transactionsDeleted(), deleted.transferLinksRemoved());
        return deleted;
    }

    private Account account(long accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new NotFoundException("Account", accountId));
    }

    /** An upload that passed {@link #checkedFile}, with the kind its bytes were judged to be. */
    private record CheckedFile(ImportFile file, FileKind kind) {
    }

    /** Upload checks (SEC-10), in order: size, name, content, and that the name's extension agrees with the content. */
    private static CheckedFile checkedFile(String originalFileName, byte[] bytes) {
        UploadCheck.checkSize(bytes.length);
        String fileName = UploadCheck.cleanFileName(originalFileName);
        UploadCheck.checkFileName(fileName);
        FileKind kind = UploadCheck.checkContent(bytes);
        UploadCheck.checkKindMatchesName(fileName, kind);
        return new CheckedFile(ImportFile.of(fileName, bytes), kind);
    }

    /**
     * Extracts a PDF's text with the limits of SEC-10. Each refusal maps to a fixed problem detail;
     * PDFBox's own messages are never passed on.
     */
    private PdfDocumentText extract(ImportFile file) {
        try {
            return pdfExtractor.extract(file);
        } catch (PdfExtractionException e) {
            ImportProblem problem = switch (e.reason()) {
                case PASSWORD_REQUIRED -> ImportProblem.PDF_ENCRYPTED;
                case TOO_COMPLEX -> ImportProblem.PDF_TOO_COMPLEX;
                case UNREADABLE -> ImportProblem.PARSE_ERROR;
            };
            throw new ImportRejectedException(problem, e.getMessage());
        }
    }

    private ParsedStatement parse(Supplier<ParsedStatement> parsing, ImportFile file, ParseContext context,
                                  Account account) {
        try {
            return parsing.get();
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

    private Optional<PdfStatementParser> selectPdf(PdfDocumentText text) {
        try {
            return parsers.selectPdf(text);
        } catch (AmbiguousFormatException e) {
            throw ambiguous(e);
        }
    }

    private StatementParser select(ImportFile file) {
        try {
            return parsers.select(file);
        } catch (UnsupportedFormatException e) {
            throw new ImportRejectedException(ImportProblem.UNSUPPORTED_FORMAT,
                    "The file is not in a supported statement format (OFX, QFX, CSV)");
        } catch (AmbiguousFormatException e) {
            throw ambiguous(e);
        }
    }

    private static ImportRejectedException ambiguous(AmbiguousFormatException e) {
        log.error("Statement parsers {} all claim the same file", e.getParserIds());
        return new ImportRejectedException(ImportProblem.AMBIGUOUS_FORMAT,
                "More than one statement format matches this file");
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

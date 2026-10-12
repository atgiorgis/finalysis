package com.finalysis.ingestion.csv;

import com.finalysis.account.AccountType;
import com.finalysis.ingestion.AccountHints;
import com.finalysis.ingestion.FormatMatch;
import com.finalysis.ingestion.ImportFile;
import com.finalysis.ingestion.ParseContext;
import com.finalysis.ingestion.ParsedStatement;
import com.finalysis.ingestion.ParsedTxn;
import com.finalysis.ingestion.SourceSection;
import com.finalysis.ingestion.StatementParseException;
import com.finalysis.ingestion.StatementParser;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Reads delimited-text exports with a snapshot of the account's saved mapping
 * ({@link CsvMappingSpec}) (ING-01, ING-02). One parser for every institution: everything
 * bank-specific is in the mapping. The parser never touches JPA.
 *
 * <p><b>Detection.</b> Any text that contains a consistent table is a {@link FormatMatch#FALLBACK}
 * match; formats with a definite signature (OFX) win. A table may start below a preamble (summary
 * lines, blank lines): see {@link #detectTable}.
 *
 * <p><b>Reading.</b> Apache Commons CSV handles quoting, doubled quotes and line breaks inside
 * quoted cells; cells are never trimmed. The mapping's encoding is decoded strictly (a UTF-8 BOM is
 * dropped), {@code skipRows} lines are skipped, blank lines are ignored, and the header row (when
 * mapped) must match the mapping's {@code headerSignature}. Rows keep their file order.
 *
 * <p><b>Rows.</b>
 * <ul>
 *   <li>{@code txnDate} is the date column; the printed date (fingerprinted) is the post-date column
 *       when mapped, else the date column. Dates parse strictly with the mapping's format.</li>
 *   <li>Amounts: see {@link CsvAmount}. SINGLE reads one signed column; DEBIT_CREDIT needs exactly
 *       one side non-zero (blank and zero count as empty) and makes debits negative and credits
 *       positive. INVERTED then negates. The printed amount is the source cell as written.</li>
 *   <li>The raw description is the description cells, as extracted, joined by one space; blank
 *       cells are skipped so they add no spaces. All blank is an error.</li>
 *   <li>The section comes from {@link #section}; the table is locked by a test because it feeds the
 *       fingerprint.</li>
 *   <li>The balance column, when mapped, becomes {@link ParsedTxn#runningBalance()} (blank = none).</li>
 *   <li>A balance-only row (valid date and balance, no amount; see {@link #balanceOnly}) is not a
 *       transaction. The oldest row (the first, or the last in a newest-first file) gives the
 *       statement's opening balance, which reconciliation checks against the balance column;
 *       anywhere else it is skipped with a warning naming its line.</li>
 * </ul>
 * The period is the earliest to the latest printed transaction date; a file without transactions
 * takes the request's period ({@link ParseContext}). CSV exports carry no account hints and no
 * closing balance; the opening balance comes only from a balance-only row.
 *
 * <p><b>Errors.</b> Any row that can't be read fails the whole file. Messages give the line and the
 * 1-based column position with its role, never cell values or header names (SEC-10). Limits:
 * {@value #MAX_ROWS} data rows and {@value #MAX_RECORD_CHARS} characters per record.
 *
 * <p>Without a mapping, {@link #parse} throws {@link CsvMappingRequiredException}; with a mapping
 * saved for other headers, {@link CsvMappingMismatchException}. The import turns both into 422
 * responses that lead the client to the preview.
 */
@Component
public class CsvStatementParser implements StatementParser {

    public static final String ID = "csv";

    static final int MAX_ROWS = 50_000;
    static final int MAX_RECORD_CHARS = 64 * 1024;
    /** A table start is searched for in this many lines (mapping skipRows allows up to 100). */
    static final int DETECTION_LINES = 100;
    /** Records counted per run: enough to tell a table from any preamble. */
    static final int DETECTION_RUN_CAP = 100;
    /** Detection reads at most this much text. */
    static final int DETECTION_CHARS = 1024 * 1024;
    static final List<Character> DETECTION_DELIMITERS = List.of(',', ';', '\t', '|');

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final char QUOTE = '"';

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean usesCsvMapping() {
        return true;
    }

    // ------------------------------------------------------------------ detection

    @Override
    public FormatMatch detect(ImportFile file) {
        return detectTable(file.bytes()).isPresent() ? FormatMatch.FALLBACK : FormatMatch.NONE;
    }

    /**
     * Finds the table in a delimited-text file, tolerating a preamble (summary lines, blank lines).
     * The text is UTF-8 when valid, else windows-1252; a NUL byte means binary, never a table.
     *
     * <p>The rule: <b>the longest run wins</b>. A run is a sequence of consecutive records (blank
     * lines skipped) that share one column count. For each delimiter {@code , ; TAB |}, runs are
     * read from the non-blank lines among the first {@value #DETECTION_LINES}; each run counts at
     * most {@value #DETECTION_RUN_CAP} records, which may extend past those lines. A run qualifies
     * when it has two or more columns and at least two records (one record only when it is the
     * whole file). The qualifying run with the most records is the table; a tie goes to the later
     * run (a summary block above a table of the same size is the preamble), and the same run under
     * two delimiters goes to the earlier delimiter. The table's first line is {@code skipRows}:
     * physical lines, blank ones included.
     *
     * <p>Why not the first run of a fixed length: a summary block (a header and a few total rows)
     * above the real table is itself a consistent run and was picked. The trade-off: a table
     * shorter than the block above it, or one split by a row with another column count within the
     * first lines, is detected at the longer part; the preview shows where, and the user can
     * change {@code skipRows}. Rows after the run are the parser's to check: each must have the
     * header's column count.
     */
    public static Optional<CsvTable> detectTable(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return Optional.empty();
            }
        }
        String text;
        String encoding;
        try {
            text = decodeStrictly(bytes, StandardCharsets.UTF_8);
            encoding = StandardCharsets.UTF_8.name();
        } catch (CharacterCodingException e) {
            text = new String(bytes, WINDOWS_1252);
            encoding = WINDOWS_1252.name();
        }
        String window = text.length() > DETECTION_CHARS ? text.substring(0, DETECTION_CHARS) : text;
        LineIndex lines = new LineIndex(window);
        List<Line> candidates = nonBlankLines(window);
        Run best = null;
        char bestDelimiter = 0;
        for (char delimiter : DETECTION_DELIMITERS) {
            int next = 0;
            for (Line line : candidates) {
                // A start inside a run read already gives a suffix of it, which is shorter.
                if (line.number() < next) {
                    continue;
                }
                Run run = run(window, lines, line, delimiter);
                next = run.next();
                boolean qualifies = run.columns() >= 2
                        && (run.records() >= 2 || run.records() == 1 && run.toEnd() && line == candidates.getFirst());
                if (qualifies && (best == null || run.records() > best.records()
                        || run.records() == best.records() && run.start() > best.start())) {
                    best = run;
                    bestDelimiter = delimiter;
                }
            }
        }
        return best == null ? Optional.empty() : Optional.of(new CsvTable(bestDelimiter, encoding, best.start()));
    }

    /** A physical line: its zero-based number and where it starts. */
    private record Line(int number, int start) {
    }

    /**
     * Consecutive records sharing one column count.
     *
     * @param start   zero-based line of the first record
     * @param columns the shared column count, or -1 when no record was read
     * @param toEnd   whether the run reached the end of the text
     * @param next    the first line a later run may start on
     */
    private record Run(int start, int columns, int records, boolean toEnd, int next) {
    }

    /** The non-blank lines among the first {@value #DETECTION_LINES} (CRLF, LF and CR each end a line). */
    private static List<Line> nonBlankLines(String text) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        for (int number = 0; number < DETECTION_LINES && start < text.length(); number++) {
            int end = start;
            while (end < text.length() && text.charAt(end) != '\r' && text.charAt(end) != '\n') {
                end++;
            }
            if (!text.substring(start, end).isBlank()) {
                lines.add(new Line(number, start));
            }
            start = afterLineBreak(text, end);
        }
        return lines;
    }

    /**
     * The run from {@code line}: up to {@value #DETECTION_RUN_CAP} records, ending before the first
     * record with another column count, or where the quoting becomes malformed.
     */
    private static Run run(String text, LineIndex lines, Line line, char delimiter) {
        int columns = -1;
        int records = 0;
        int next = line.number() + 1;
        try (CSVParser parser = format(delimiter).parse(readerFrom(text, line.start()))) {
            for (CSVRecord record : parser) {
                if (isBlank(record)) {
                    continue;
                }
                int recordLine = lines.lineAt(skipLineBreaks(text,
                        line.start() + Math.toIntExact(record.getCharacterPosition()))) - 1;
                if (columns == -1) {
                    columns = record.size();
                } else if (record.size() != columns) {
                    return new Run(line.number(), columns, records, false, recordLine);
                }
                next = recordLine + 1;
                if (++records == DETECTION_RUN_CAP) {
                    return new Run(line.number(), columns, records, false, next);
                }
            }
            return new Run(line.number(), columns, records, true, next);
        } catch (IOException | UncheckedIOException | IllegalStateException e) {
            return new Run(line.number(), columns, records, false, next);
        }
    }

    /** Reads {@code text} from {@code start} without copying it. */
    private static Reader readerFrom(String text, int start) throws IOException {
        StringReader reader = new StringReader(text);
        reader.skip(start);
        return reader;
    }

    // ------------------------------------------------------------------ parsing

    /**
     * A file without rows is a statement without transactions when the context carries the
     * request's period, and a parse error otherwise.
     *
     * @throws CsvMappingRequiredException if the context has no mapping
     * @throws CsvMappingMismatchException if the file's header or column count doesn't match the mapping
     * @throws StatementParseException     if the file or any row can't be read
     */
    @Override
    public ParsedStatement parse(ImportFile file, ParseContext context) {
        if (context.csvMapping() == null) {
            throw new CsvMappingRequiredException();
        }
        CsvMappingValues mapping = context.csvMapping().values();
        String text = decode(file.bytes(), mapping.encoding());
        char delimiter = mapping.delimiter().charAt(0);
        int maxRecords = MAX_ROWS + (mapping.headerRow() ? 1 : 0);
        List<RawRecord> records = records(text, afterLines(text, mapping.skipRows()), delimiter, maxRecords);
        Layout layout = layout(mapping, records);
        DateTimeFormatter dates = CsvDateFormat.formatter(mapping.dateFormat())
                .orElseThrow(() -> new StatementParseException(null, "the CSV mapping's date format is invalid"));

        List<RawRecord> rows = records.subList(mapping.headerRow() ? 1 : 0, records.size());
        List<ParsedTxn> txns = new ArrayList<>(rows.size());
        Map<Integer, BigDecimal> balanceOnly = new TreeMap<>();
        for (int i = 0; i < rows.size(); i++) {
            RawRecord row = rows.get(i);
            BigDecimal balance = balanceOnly(row, layout, mapping, dates);
            if (balance != null) {
                balanceOnly.put(i, balance);
            } else {
                txns.add(txn(row, layout, mapping, dates, context.accountType()));
            }
        }

        // The opening row is the oldest: the first, or the last in a newest-first file (as reconciliation orders rows).
        boolean newestFirst = txns.size() >= 2 && txns.getFirst().printedDate().isAfter(txns.getLast().printedDate());
        int openingRow = newestFirst ? rows.size() - 1 : 0;
        BigDecimal opening = null;
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<Integer, BigDecimal> entry : balanceOnly.entrySet()) {
            if (entry.getKey() == openingRow) {
                opening = entry.getValue();
            } else {
                warnings.add("line " + rows.get(entry.getKey()).line()
                        + ": skipped a row with a balance but no amount (not a transaction)");
            }
        }

        if (txns.isEmpty()) {
            if (context.periodStart() == null || context.periodEnd() == null) {
                throw new StatementParseException(null,
                        "the file has no transactions; send periodStart and periodEnd to import it as an empty statement");
            }
            return new ParsedStatement(AccountHints.none(), context.periodStart(), context.periodEnd(), opening, null,
                    Map.of(), List.of(), warnings);
        }
        LocalDate start = txns.stream().map(ParsedTxn::printedDate).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate end = txns.stream().map(ParsedTxn::printedDate).max(Comparator.naturalOrder()).orElseThrow();
        return new ParsedStatement(AccountHints.none(), start, end, opening, null, Map.of(), txns, warnings);
    }

    /**
     * The balance of a balance-only row, or null when the row is something else. A balance-only
     * row has a valid date and a balance but no amount (blank amount, or blank debit and credit);
     * it needs a mapped balance column, so without one an empty amount stays a parse error. Judged
     * by the cells' shape alone, never by description text, so it holds for any bank.
     */
    private static BigDecimal balanceOnly(RawRecord row, Layout layout, CsvMappingValues mapping,
                                          DateTimeFormatter dates) {
        if (layout.balance() == null || row.values().size() != layout.columns()
                || cell(row, layout.balance()).isBlank()) {
            return null;
        }
        boolean noAmount = mapping.amountMode() == CsvAmountMode.SINGLE
                ? cell(row, layout.amount()).isBlank()
                : cell(row, layout.debit()).isBlank() && cell(row, layout.credit()).isBlank();
        if (!noAmount) {
            return null;
        }
        try {
            LocalDate.parse(cell(row, layout.date()).strip(), dates);
        } catch (DateTimeParseException e) {
            return null;
        }
        return amount(row, layout.balance(), "balance");
    }

    /**
     * Section by sign, CHECK when the row has a check number (ING-02). Zero counts as money in.
     * Locked by a test: the section is part of the fingerprint.
     */
    static SourceSection section(AccountType accountType, BigDecimal amount, boolean hasCheckNumber) {
        if (hasCheckNumber) {
            return SourceSection.CHECK;
        }
        boolean moneyIn = amount.signum() >= 0;
        if (accountType == AccountType.CREDIT_CARD) {
            return moneyIn ? SourceSection.PAYMENT : SourceSection.PURCHASE;
        }
        return moneyIn ? SourceSection.DEPOSIT : SourceSection.WITHDRAWAL;
    }

    private static ParsedTxn txn(RawRecord row, Layout layout, CsvMappingValues mapping, DateTimeFormatter dates,
                                 AccountType accountType) {
        if (row.values().size() != layout.columns()) {
            throw new StatementParseException(row.line(),
                    "has " + row.values().size() + " columns; expected " + layout.columns());
        }
        LocalDate date = date(row, layout.date(), "date", dates);
        LocalDate postDate = layout.postDate() == null ? null : date(row, layout.postDate(), "post date", dates);

        Amount amount = mapping.amountMode() == CsvAmountMode.SINGLE
                ? single(row, layout.amount())
                : debitCredit(row, layout.debit(), layout.credit());
        BigDecimal signed = mapping.sign() == CsvSign.INVERTED ? amount.value().negate() : amount.value();

        String checkNumber = null;
        if (layout.checkNumber() != null && !cell(row, layout.checkNumber()).isBlank()) {
            checkNumber = cell(row, layout.checkNumber()).strip();
        }
        BigDecimal balance = null;
        if (layout.balance() != null && !cell(row, layout.balance()).isBlank()) {
            balance = amount(row, layout.balance(), "balance");
        }

        try {
            return new ParsedTxn(postDate != null ? postDate : date, date, postDate, description(row, layout),
                    amount.printed(), signed, section(accountType, signed, checkNumber != null), checkNumber, null,
                    row.line(), row.text(), balance);
        } catch (IllegalArgumentException e) {
            // ParsedTxn's messages name fields and limits only.
            throw new StatementParseException(row.line(), e.getMessage());
        }
    }

    private static LocalDate date(RawRecord row, int column, String role, DateTimeFormatter dates) {
        try {
            return LocalDate.parse(cell(row, column).strip(), dates);
        } catch (DateTimeParseException e) {
            throw cellError(row, column, role, "is not a date in the mapping's date format");
        }
    }

    private record Amount(BigDecimal value, String printed) {
    }

    private static Amount single(RawRecord row, int column) {
        String cell = cell(row, column);
        if (cell.isBlank()) {
            throw cellError(row, column, "amount", "is empty");
        }
        return new Amount(amount(row, column, "amount"), cell);
    }

    private static Amount debitCredit(RawRecord row, int debitColumn, int creditColumn) {
        BigDecimal debit = nonZeroOrNull(row, debitColumn, "debit");
        BigDecimal credit = nonZeroOrNull(row, creditColumn, "credit");
        String columns = "debit (column " + (debitColumn + 1) + ") and credit (column " + (creditColumn + 1) + ")";
        if (debit != null && credit != null) {
            throw new StatementParseException(row.line(), columns + " both have amounts");
        }
        if (debit == null && credit == null) {
            throw new StatementParseException(row.line(), columns + " are both empty");
        }
        // Unsigned columns: a debit is money out whether or not the bank printed a minus.
        return debit != null
                ? new Amount(debit.abs().negate(), cell(row, debitColumn))
                : new Amount(credit.abs(), cell(row, creditColumn));
    }

    /** Blank, 0 and 0.00 are all empty. */
    private static BigDecimal nonZeroOrNull(RawRecord row, int column, String role) {
        if (cell(row, column).isBlank()) {
            return null;
        }
        BigDecimal value = amount(row, column, role);
        return value.signum() == 0 ? null : value;
    }

    private static BigDecimal amount(RawRecord row, int column, String role) {
        try {
            return CsvAmount.parse(cell(row, column));
        } catch (IllegalArgumentException e) {
            throw cellError(row, column, role, e.getMessage());
        }
    }

    /** Description cells exactly as extracted, joined by one space; blank cells are skipped. */
    private static String description(RawRecord row, Layout layout) {
        List<String> parts = new ArrayList<>();
        for (int column : layout.descriptions()) {
            String cell = cell(row, column);
            if (!cell.isBlank()) {
                parts.add(cell);
            }
        }
        if (parts.isEmpty()) {
            throw new StatementParseException(row.line(), "every description column is empty");
        }
        return String.join(" ", parts);
    }

    private static String cell(RawRecord row, int column) {
        return row.values().get(column);
    }

    private static StatementParseException cellError(RawRecord row, int column, String role, String reason) {
        return new StatementParseException(row.line(), "column " + (column + 1) + " (" + role + ") " + reason);
    }

    // ------------------------------------------------------------------ layout

    /** Zero-based column positions of every mapped role; nullable for optional roles. */
    private record Layout(int columns, int date, Integer postDate, List<Integer> descriptions, Integer amount,
                          Integer debit, Integer credit, Integer balance, Integer checkNumber) {
    }

    /** Checks the header (or column count) against the mapping and resolves its columns. */
    private static Layout layout(CsvMappingValues mapping, List<RawRecord> records) {
        if (records.isEmpty()) {
            throw new StatementParseException(null, mapping.headerRow()
                    ? "the file has no header row" : "the file has no transactions");
        }
        RawRecord first = records.getFirst();
        String signature = signatureOf(first, mapping.headerRow());
        if (!signature.equals(mapping.headerSignature())) {
            throw new CsvMappingMismatchException(mapping.headerSignature(), signature);
        }

        Resolver resolve = new Resolver(mapping.headerRow() ? first.values().stream().map(String::strip).toList() : null);
        return new Layout(first.values().size(),
                resolve.column(mapping.dateColumn()),
                resolve.optional(mapping.postDateColumn()),
                mapping.descriptionColumns().stream().map(resolve::column).toList(),
                resolve.optional(mapping.amountColumn()),
                resolve.optional(mapping.debitColumn()),
                resolve.optional(mapping.creditColumn()),
                resolve.optional(mapping.balanceColumn()),
                resolve.optional(mapping.checkNumberColumn()));
    }

    private static String signatureOf(RawRecord first, boolean headerRow) {
        return headerRow
                ? CsvHeaderSignature.ofHeaders(first.values())
                : CsvHeaderSignature.ofColumnCount(first.values().size());
    }

    /**
     * The file's header signature when read with the mapping's encoding, delimiter, skipped lines
     * and header setting, or null when it can't be read that way. Equal to the mapping's own
     * signature exactly when {@link #parse} won't report a mismatch.
     */
    static String headerSignature(byte[] bytes, CsvMappingValues mapping) {
        try {
            String text = decode(bytes, mapping.encoding());
            List<RawRecord> records = records(text, afterLines(text, mapping.skipRows()),
                    mapping.delimiter().charAt(0), MAX_ROWS + 1);
            return records.isEmpty() ? null : signatureOf(records.getFirst(), mapping.headerRow());
        } catch (StatementParseException e) {
            return null;
        }
    }

    /** Every non-blank record of a detected table, header included. */
    static List<RawRecord> tableRecords(byte[] bytes, CsvTable table) {
        String text = decode(bytes, table.encoding());
        return records(text, afterLines(text, table.skipRows()), table.delimiter(), MAX_ROWS + 1);
    }

    /** Header names (matched exactly after stripping), or zero-based indexes without a header row. */
    private record Resolver(List<String> headers) {

        Integer optional(String column) {
            return column == null ? null : column(column);
        }

        int column(String column) {
            int index = headers != null ? headers.indexOf(column) : Integer.parseInt(column);
            if (index < 0) {
                // The mapping was validated against these headers when saved; only a hand-edited row gets here.
                throw new StatementParseException(null, "the CSV mapping references a column the file doesn't have");
            }
            return index;
        }
    }

    // ------------------------------------------------------------------ records

    /**
     * One non-blank CSV record.
     *
     * @param line 1-based line where the record starts
     * @param text the record as it appears in the file, line breaks inside quoted cells included
     */
    record RawRecord(List<String> values, int line, String text) {
    }

    /**
     * Reads the non-blank records from {@code start} on, with their source lines and text.
     *
     * @throws StatementParseException at the failing record's line for malformed quoting, a record
     *                                 over {@value #MAX_RECORD_CHARS} characters or more than
     *                                 {@code maxRecords} records
     */
    static List<RawRecord> records(String text, int start, char delimiter, int maxRecords) {
        LineIndex lines = new LineIndex(text);
        List<RawRecord> records = new ArrayList<>();
        int end = start;
        try (CSVParser parser = format(delimiter).parse(new StringReader(text.substring(start)))) {
            var iterator = parser.iterator();
            while (true) {
                CSVRecord record;
                try {
                    if (!iterator.hasNext()) {
                        break;
                    }
                    record = iterator.next();
                } catch (UncheckedIOException | IllegalStateException e) {
                    throw new StatementParseException(lines.lineAt(skipLineBreaks(text, end)),
                            "malformed quoting (an unclosed quote, or text after a closing quote)");
                }
                int recordStart = skipLineBreaks(text, start + Math.toIntExact(record.getCharacterPosition()));
                end = recordEnd(text, recordStart, delimiter);
                int line = lines.lineAt(recordStart);
                if (end - recordStart > MAX_RECORD_CHARS) {
                    throw new StatementParseException(line, "record is longer than " + MAX_RECORD_CHARS + " characters");
                }
                if (isBlank(record)) {
                    continue;
                }
                if (records.size() == maxRecords) {
                    throw new StatementParseException(line, "the file has more than " + MAX_ROWS + " rows");
                }
                records.add(new RawRecord(record.toList(), line, text.substring(recordStart, end)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e); // a StringReader doesn't fail
        }
        return records;
    }

    /**
     * Where the record starting at {@code start} ends: the first line break outside a quoted cell,
     * or the end of the text. A quote opens a quoted cell only at the start of a cell, as in
     * Commons CSV; inside one, a doubled quote is a literal quote.
     */
    private static int recordEnd(String text, int start, char delimiter) {
        boolean quoted = false;
        boolean cellStart = true;
        int i = start;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == QUOTE) {
                    if (i + 1 < text.length() && text.charAt(i + 1) == QUOTE) {
                        i++;
                    } else {
                        quoted = false;
                    }
                }
            } else if (c == '\r' || c == '\n') {
                break;
            } else if (c == delimiter) {
                cellStart = true;
            } else {
                quoted = c == QUOTE && cellStart;
                cellStart = false;
            }
            i++;
        }
        return i;
    }

    /** Commons CSV reports a record's position before the empty lines it skipped. */
    private static int skipLineBreaks(String text, int position) {
        while (position < text.length() && (text.charAt(position) == '\r' || text.charAt(position) == '\n')) {
            position++;
        }
        return position;
    }

    /** A blank line: one cell holding nothing but whitespace (empty lines never reach here). */
    private static boolean isBlank(CSVRecord record) {
        return record.size() == 1 && record.get(0).isBlank();
    }

    private static CSVFormat format(char delimiter) {
        return CSVFormat.Builder.create()
                .setDelimiter(delimiter)
                .setQuote(QUOTE)
                .setEscape(null)
                .setCommentMarker(null)
                .setIgnoreEmptyLines(true)
                .setTrim(false)
                .setIgnoreSurroundingSpaces(false)
                .get();
    }

    // ------------------------------------------------------------------ text

    private static String decode(byte[] bytes, String encoding) {
        Charset charset = Charset.forName(encoding);
        try {
            return decodeStrictly(bytes, charset);
        } catch (CharacterCodingException e) {
            throw new StatementParseException(null, "the file is not valid " + charset.name() + " text");
        }
    }

    /** Strict decoding; a UTF-8 BOM is dropped. */
    private static String decodeStrictly(byte[] bytes, Charset charset) throws CharacterCodingException {
        int offset = charset.equals(StandardCharsets.UTF_8)
                && Arrays.equals(bytes, 0, Math.min(3, bytes.length), UTF8_BOM, 0, 3) ? 3 : 0;
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                .toString();
    }

    /** The position after the first {@code count} lines (CRLF, LF or CR), or the end of the text. */
    static int afterLines(String text, int count) {
        int position = 0;
        for (int skipped = 0; skipped < count && position < text.length(); skipped++) {
            while (position < text.length() && text.charAt(position) != '\r' && text.charAt(position) != '\n') {
                position++;
            }
            position = afterLineBreak(text, position);
        }
        return position;
    }

    /** The position after the line break (CRLF, LF or CR) at {@code position}, if there is one. */
    private static int afterLineBreak(String text, int position) {
        if (position < text.length() && text.charAt(position) == '\r') {
            position++;
        }
        if (position < text.length() && text.charAt(position) == '\n') {
            position++;
        }
        return position;
    }

    /** Maps character positions to 1-based line numbers; CRLF, LF and CR each end a line. */
    private static final class LineIndex {

        private final int[] lineStarts;

        LineIndex(String text) {
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n' || c == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n')) {
                    starts.add(i + 1);
                }
            }
            lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        }

        int lineAt(int position) {
            int index = Arrays.binarySearch(lineStarts, position);
            return index >= 0 ? index + 1 : -index - 1;
        }
    }
}

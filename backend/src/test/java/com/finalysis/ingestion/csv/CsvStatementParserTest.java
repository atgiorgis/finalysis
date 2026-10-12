package com.finalysis.ingestion.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.finalysis.account.AccountType;
import com.finalysis.ingestion.FormatMatch;
import com.finalysis.ingestion.ImportFile;
import com.finalysis.ingestion.ParseContext;
import com.finalysis.ingestion.ParsedStatement;
import com.finalysis.ingestion.ParsedTxn;
import com.finalysis.ingestion.SourceSection;
import com.finalysis.ingestion.StatementParseException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** CSV parsing rules on small inline files; the sample data is covered by {@link CsvSampleDataParseTest}. */
class CsvStatementParserTest {

    /** Put into cells of failing rows: no error message may echo it (SEC-10). */
    private static final String MARKER = "QZX7MARKER";
    private static final String SIMPLE_HEADER = "Date,Description,Amount\n";
    private static final String NORTHFIELD_HEADER = "Date,Description,Debit,Credit,Balance\n";

    private final CsvStatementParser parser = new CsvStatementParser();

    // ------------------------------------------------------------------ detection

    @ParameterizedTest
    @ValueSource(strings = {
            "a,b\n1,2\n",
            "a;b;c\n1;2;3\n",
            "a\tb\n1\t2\n",
            "a|b\n1|2\n",
            "a,b",
            "\n\na,b\n\n   \n1,2\n",
            "Date,Description,Amount\n2026-04-01,\"CAFE, ARLINGTON\",-4.50\n"})
    void consistentTablesAreFallbackMatches(String text) {
        assertThat(parser.detect(file(text))).isEqualTo(FormatMatch.FALLBACK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "\n\n  \n",
            "one column\nonly\n",
            "a,b\n1,2,3\n",
            "a,b\n\"unclosed,2\n",
            "OFXHEADER:100\nDATA:OFXSGML\n<OFX>\n"})
    void anythingElseIsNoMatch(String text) {
        assertThat(parser.detect(file(text))).isEqualTo(FormatMatch.NONE);
    }

    @Test
    void aTableIsFiveConsistentRecordsOrAllThatRemain() {
        // Five consistent records make a table; later rows are the parser's to check.
        assertThat(parser.detect(file("a,b\n" + "1,2\n\n".repeat(4) + "1,2,3\n"))).isEqualTo(FormatMatch.FALLBACK);
        // Four, then a break, and no later start has two consistent records left.
        assertThat(parser.detect(file("a,b\n" + "1,2\n".repeat(3) + "1,2,3\n"))).isEqualTo(FormatMatch.NONE);
        // Fewer than five records are a table when all of them agree.
        assertThat(CsvStatementParser.detectTable(bytes("a,b\n1,2\n"))).contains(new CsvTable(',', "UTF-8", 0));
    }

    @Test
    void aTableMayStartBelowAPreamble() {
        String preamble = "Account Activity\nAccount number,XXXXXX1001\nPeriod,04/01/2026 to 04/30/2026\n"
                + "Opening balance,3200.00\n\n";
        String table = NORTHFIELD_HEADER + "04/01/2026,RENT,1850.00,,1350.00\n".repeat(6);

        // The two-column preamble lines are only three in a row, so the table starts at the header (line 6).
        assertThat(CsvStatementParser.detectTable(bytes(preamble + table))).contains(new CsvTable(',', "UTF-8", 5));
        // A short table below a preamble: all remaining records, header and one row.
        assertThat(CsvStatementParser.detectTable(bytes("Summary\n" + SIMPLE_HEADER + "2026-04-01,CAFE,-4.50\n")))
                .contains(new CsvTable(',', "UTF-8", 1));
        // A lone last line is not a table.
        assertThat(parser.detect(file("Summary\nTotal\n1,2\n"))).isEqualTo(FormatMatch.NONE);
    }

    @Test
    void aTableMustStartWithinTheFirstHundredLines() {
        assertThat(CsvStatementParser.detectTable(bytes("note\n".repeat(99) + "a,b\n1,2\n")))
                .contains(new CsvTable(',', "UTF-8", 99));
        assertThat(parser.detect(file("note\n".repeat(100) + "a,b\n1,2\n"))).isEqualTo(FormatMatch.NONE);
    }

    @Test
    void theEarliestTableStartWinsAcrossDelimiters() {
        // Semicolons agree from the first line; commas only from the second.
        String text = "x;y\n1,5;2\n1,2;3\n" + "4,5;6\n".repeat(5);

        assertThat(CsvStatementParser.detectTable(bytes(text))).contains(new CsvTable(';', "UTF-8", 0));
        // A tie goes to the earlier delimiter in , ; TAB |.
        assertThat(CsvStatementParser.detectTable(bytes("a,b;c\n1,2;3\n"))).contains(new CsvTable(',', "UTF-8", 0));
    }

    @Test
    void aQuoteInThePreambleDoesNotHideTheTable() {
        // The quote opens a cell that never closes, so reading from line 1 fails; line 2 starts the table.
        String text = "\"Note: unclosed\n" + SIMPLE_HEADER + "2026-04-01,CAFE,-4.50\n".repeat(5);

        assertThat(CsvStatementParser.detectTable(bytes(text))).contains(new CsvTable(',', "UTF-8", 1));
    }

    @Test
    void binaryAndWindows1252ContentIsJudgedWithoutThrowing() {
        assertThat(parser.detect(ImportFile.of("x.csv", new byte[] {'a', ',', 0, '\n'}))).isEqualTo(FormatMatch.NONE);
        byte[] windows1252 = "Date,Description\n04/01/2026,CAFÉ\n".getBytes(Charset.forName("windows-1252"));
        assertThat(parser.detect(ImportFile.of("x.csv", windows1252))).isEqualTo(FormatMatch.FALLBACK);
        assertThat(CsvStatementParser.detectTable(windows1252)).contains(new CsvTable(',', "windows-1252", 0));
    }

    // ------------------------------------------------------------------ mapping

    @Test
    void parsingWithoutAMappingRequiresOne() {
        assertThatThrownBy(() -> parser.parse(file(SIMPLE_HEADER + "2026-04-01,CAFE,-4.50\n"),
                new ParseContext(AccountType.CHECKING)))
                .isInstanceOf(CsvMappingRequiredException.class);
    }

    @Test
    void differentHeadersAreAMappingMismatch() {
        TestCsvMapping mapping = TestCsvMapping.simple();

        assertThatThrownBy(() -> parse("Date,Details,Amount\n2026-04-01,CAFE,-4.50\n", mapping))
                .isInstanceOfSatisfying(CsvMappingMismatchException.class, e -> {
                    assertThat(e.getExpectedSignature()).isEqualTo(CsvHeaderSignature.ofHeaders(mapping.headers));
                    assertThat(e.getActualSignature())
                            .isEqualTo(CsvHeaderSignature.ofHeaders(List.of("Date", "Details", "Amount")));
                });
    }

    @Test
    void headerNamesMatchAfterStrippingButCaseMatters() {
        assertThat(parse(" Date , Description,Amount \n2026-04-01,CAFE,-4.50\n", TestCsvMapping.simple()).txns())
                .hasSize(1);
        assertThatThrownBy(() -> parse("date,description,amount\n2026-04-01,CAFE,-4.50\n", TestCsvMapping.simple()))
                .isInstanceOf(CsvMappingMismatchException.class);
    }

    @Test
    void withoutAHeaderRowColumnsAreIndexesAndTheColumnCountIsTheSignature() {
        TestCsvMapping mapping = new TestCsvMapping();
        mapping.headerRow = false;
        mapping.columnCount = 4;
        mapping.dateColumn = "2";
        mapping.dateFormat = "MM/dd/yyyy";
        mapping.descriptionColumns = List.of("0");
        mapping.amountColumn = "3";

        ParsedStatement parsed = parse("CAFE,ignored,04/01/2026,-4.50\nDELI,ignored,04/02/2026,-6.00\n", mapping);

        assertThat(parsed.txns()).extracting(ParsedTxn::rawDescription).containsExactly("CAFE", "DELI");
        assertThat(parsed.txns()).extracting(ParsedTxn::sourceLineNumber).containsExactly(1, 2);
        assertThatThrownBy(() -> parse("CAFE,04/01/2026,-4.50\n", mapping))
                .isInstanceOfSatisfying(CsvMappingMismatchException.class,
                        e -> assertThat(e.getActualSignature()).isEqualTo("columns:3"));
    }

    @Test
    void skipRowsSkipsPreambleLinesAndLineNumbersStillCountThem() {
        TestCsvMapping mapping = TestCsvMapping.simple();
        mapping.skipRows = 3;
        String text = "Northfield export\r\nAccount \"Checking\", ****1001\r\n\r\n"
                + "Date,Description,Amount\r\n2026-04-01,CAFE,-4.50\r\n";

        ParsedTxn txn = parse(text, mapping).txns().getFirst();

        assertThat(txn.sourceLineNumber()).isEqualTo(5);
        assertThat(txn.sourceLineText()).isEqualTo("2026-04-01,CAFE,-4.50");
    }

    @Test
    void otherDelimitersAndAUtf8BomAreApplied() {
        TestCsvMapping mapping = TestCsvMapping.simple();
        mapping.delimiter = ";";
        byte[] bytes = "\uFEFFDate;Description;Amount\n2026-04-01;CAFE, ARLINGTON;-4.50\n".getBytes(StandardCharsets.UTF_8);

        ParsedTxn txn = parser.parse(ImportFile.of("x.csv", bytes), new ParseContext(AccountType.CHECKING, mapping.build()))
                .txns().getFirst();

        assertThat(txn.rawDescription()).isEqualTo("CAFE, ARLINGTON");
        assertThat(txn.amount()).isEqualTo(new BigDecimal("-4.50"));
        assertThat(txn.printedAmount()).isEqualTo("-4.50");
    }

    @Test
    void aFileWithoutRowsIsAParseErrorWithoutARequestPeriod() {
        assertThatThrownBy(() -> parse(SIMPLE_HEADER, TestCsvMapping.simple()))
                .isInstanceOf(StatementParseException.class)
                .hasMessage("the file has no transactions; send periodStart and periodEnd to import it as an empty statement");
        assertThatThrownBy(() -> parser.parse(file(SIMPLE_HEADER), new ParseContext(AccountType.CHECKING,
                TestCsvMapping.simple().build(), LocalDate.of(2026, 4, 1), null)))
                .isInstanceOf(StatementParseException.class);
        assertThatThrownBy(() -> parse("\n\n", TestCsvMapping.simple()))
                .hasMessage("the file has no header row");
    }

    @Test
    void aFileWithoutRowsIsAnEmptyStatementForTheRequestPeriod() {
        ParsedStatement parsed = parser.parse(file(SIMPLE_HEADER), new ParseContext(AccountType.SAVINGS,
                TestCsvMapping.simple().build(), LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)));

        assertThat(parsed.txns()).isEmpty();
        assertThat(parsed.periodStart()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(parsed.periodEnd()).isEqualTo(LocalDate.of(2026, 4, 30));
    }

    // ------------------------------------------------------------------ dates

    @Test
    void postDateIsThePrintedDateWhenMapped() {
        TestCsvMapping mapping = TestCsvMapping.apex();
        ParsedTxn txn = parse(String.join(",", mapping.headers) + "\n2026-04-03,2026-04-05,CAFE,Dining,4.50\n", mapping)
                .txns().getFirst();

        assertThat(txn.txnDate()).isEqualTo(LocalDate.of(2026, 4, 3));
        assertThat(txn.postDate()).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(txn.printedDate()).isEqualTo(LocalDate.of(2026, 4, 5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-02-30", "2026-4-01", "04/01/2026", "", "2026-04-01T00:00"})
    void datesParseStrictlyWithTheMappingsFormat(String date) {
        assertThatThrownBy(() -> parse(SIMPLE_HEADER + "2026-04-01,OK,-1.00\n" + date + ",CAFE,-4.50\n", TestCsvMapping.simple()))
                .isInstanceOf(StatementParseException.class)
                .hasMessage("line 3: column 1 (date) is not a date in the mapping's date format")
                .satisfies(e -> assertThat(((StatementParseException) e).getLineNumber()).isEqualTo(3));
    }

    // ------------------------------------------------------------------ amounts

    @Test
    void singleAmountKeepsThePrintedCellAndRejectsBlank() {
        ParsedTxn txn = parse(SIMPLE_HEADER + "2026-04-01,CAFE,\"($1,204.50)\"\n", TestCsvMapping.simple()).txns().getFirst();

        assertThat(txn.amount()).isEqualTo(new BigDecimal("-1204.50"));
        assertThat(txn.printedAmount()).isEqualTo("($1,204.50)");
        assertThatThrownBy(() -> parse(SIMPLE_HEADER + "2026-04-01,CAFE, \n", TestCsvMapping.simple()))
                .hasMessage("line 2: column 3 (amount) is empty");
        assertThatThrownBy(() -> parse(SIMPLE_HEADER + "2026-04-01,CAFE,4.505\n", TestCsvMapping.simple()))
                .hasMessage("line 2: column 3 (amount) has more than two decimal places");
    }

    @ParameterizedTest(name = "debit [{0}] credit [{1}] -> {2}")
    @CsvSource(delimiter = '|', value = {
            "12.00     |           | -12.00   | 12.00",
            "          | 12.00     | 12.00    | 12.00",
            "0.00      | 5.00      | 5.00     | 5.00",
            "5.00      | 0         | -5.00    | 5.00",
            "-5.00     |           | -5.00    | -5.00",
            "$1,200.00 |           | -1200.00 | $1,200.00",
            "' '       | (7.25)    | 7.25     | (7.25)",
    })
    void debitCreditNeedsExactlyOneNonZeroSide(String debit, String credit, String amount, String printed) {
        ParsedTxn txn = parse(NORTHFIELD_HEADER + northfieldRow(debit, credit), TestCsvMapping.northfield()).txns().getFirst();

        assertThat(txn.amount()).isEqualTo(new BigDecimal(amount));
        assertThat(txn.printedAmount()).isEqualTo(printed);
    }

    @ParameterizedTest(name = "debit [{0}] credit [{1}]")
    @CsvSource(delimiter = '|', value = {
            "12.00 | 3.00  | both have amounts",
            "      |       | are both empty",
            "0     | 0.00  | are both empty",
            "0.00  |       | are both empty",
            "' '   | ''    | are both empty",
    })
    void debitCreditRejectsBothOrNeither(String debit, String credit, String problem) {
        assertThatThrownBy(() -> parse(NORTHFIELD_HEADER + northfieldRow(debit, credit), TestCsvMapping.northfield()))
                .isInstanceOf(StatementParseException.class)
                .hasMessage("line 2: debit (column 3) and credit (column 4) " + problem);
    }

    @Test
    void invertedSignNegatesSingleAndDebitCreditAmounts() {
        TestCsvMapping single = TestCsvMapping.simple();
        single.sign = CsvSign.INVERTED;
        TestCsvMapping debitCredit = TestCsvMapping.northfield();
        debitCredit.sign = CsvSign.INVERTED;

        assertThat(parse(SIMPLE_HEADER + "2026-04-01,CAFE,4.50\n2026-04-02,REFUND,-1.00\n", single, AccountType.CREDIT_CARD)
                .txns()).extracting(ParsedTxn::amount, ParsedTxn::section).containsExactly(
                        tuple(new BigDecimal("-4.50"), SourceSection.PURCHASE),
                        tuple(new BigDecimal("1.00"), SourceSection.PAYMENT));
        assertThat(parse(NORTHFIELD_HEADER + northfieldRow("4.50", ""), debitCredit).txns().getFirst().amount())
                .isEqualTo(new BigDecimal("4.50"));
    }

    // ------------------------------------------------------------------ sections

    static Stream<Arguments> sectionTable() {
        return Stream.of(
                Arguments.of(AccountType.CHECKING, "-0.01", false, SourceSection.WITHDRAWAL),
                Arguments.of(AccountType.CHECKING, "0.00", false, SourceSection.DEPOSIT),
                Arguments.of(AccountType.CHECKING, "0.01", false, SourceSection.DEPOSIT),
                Arguments.of(AccountType.SAVINGS, "-0.01", false, SourceSection.WITHDRAWAL),
                Arguments.of(AccountType.SAVINGS, "0.00", false, SourceSection.DEPOSIT),
                Arguments.of(AccountType.SAVINGS, "0.01", false, SourceSection.DEPOSIT),
                Arguments.of(AccountType.CREDIT_CARD, "-0.01", false, SourceSection.PURCHASE),
                Arguments.of(AccountType.CREDIT_CARD, "0.00", false, SourceSection.PAYMENT),
                Arguments.of(AccountType.CREDIT_CARD, "0.01", false, SourceSection.PAYMENT),
                Arguments.of(AccountType.CHECKING, "-0.01", true, SourceSection.CHECK),
                Arguments.of(AccountType.CHECKING, "0.01", true, SourceSection.CHECK),
                Arguments.of(AccountType.SAVINGS, "-0.01", true, SourceSection.CHECK),
                Arguments.of(AccountType.CREDIT_CARD, "-0.01", true, SourceSection.CHECK));
    }

    /** Locked: the section is part of the fingerprint (ING-03), so changing this table re-inserts rows. */
    @ParameterizedTest(name = "{0} {1} check={2} -> {3}")
    @MethodSource("sectionTable")
    void sectionTableIsLocked(AccountType type, String amount, boolean check, SourceSection expected) {
        assertThat(CsvStatementParser.section(type, new BigDecimal(amount), check)).isEqualTo(expected);
    }

    @Test
    void aCheckNumberMakesTheRowACheck() {
        TestCsvMapping mapping = TestCsvMapping.northfield();
        mapping.headers = List.of("Date", "Check", "Description", "Debit", "Credit", "Balance");
        mapping.checkNumberColumn = "Check";
        String text = "Date,Check,Description,Debit,Credit,Balance\n"
                + "04/01/2026, 1042 ,CHECK,100.00,,900.00\n"
                + "04/02/2026,,ATM,20.00,,880.00\n";

        List<ParsedTxn> txns = parse(text, mapping).txns();

        assertThat(txns).extracting(ParsedTxn::section).containsExactly(SourceSection.CHECK, SourceSection.WITHDRAWAL);
        assertThat(txns).extracting(ParsedTxn::checkNumber).containsExactly("1042", null);
    }

    // ------------------------------------------------------------------ descriptions

    @ParameterizedTest(name = "[{0}] + [{1}] -> [{2}]")
    @CsvSource(delimiter = '|', value = {
            "'AMAZON'  | 'ORDER 12' | 'AMAZON ORDER 12'",
            "'AMAZON'  | ''         | 'AMAZON'",
            "''        | 'ORDER 12' | 'ORDER 12'",
            "'AMAZON'  | '   '      | 'AMAZON'",
            "'AMAZON ' | ' ORDER'   | 'AMAZON   ORDER'",
            "' A  B '  | 'C'        | ' A  B  C'",
    })
    void descriptionCellsJoinWithOneSpaceAsExtractedSkippingBlanks(String first, String second, String expected) {
        TestCsvMapping mapping = TestCsvMapping.simple();
        mapping.headers = List.of("Date", "Description", "Memo", "Amount");
        mapping.descriptionColumns = List.of("Description", "Memo");

        ParsedTxn txn = parse("Date,Description,Memo,Amount\n2026-04-01,\"" + first + "\",\"" + second + "\",-1.00\n", mapping)
                .txns().getFirst();

        assertThat(txn.rawDescription()).isEqualTo(expected);
    }

    @Test
    void descriptionColumnOrderComesFromTheMapping() {
        TestCsvMapping mapping = TestCsvMapping.simple();
        mapping.headers = List.of("Date", "Description", "Memo", "Amount");
        mapping.descriptionColumns = List.of("Memo", "Description");

        assertThat(parse("Date,Description,Memo,Amount\n2026-04-01,AMAZON,ORDER 12,-1.00\n", mapping)
                .txns().getFirst().rawDescription()).isEqualTo("ORDER 12 AMAZON");
    }

    @Test
    void allDescriptionCellsEmptyIsAnError() {
        assertThatThrownBy(() -> parse(SIMPLE_HEADER + "2026-04-01,  ,-1.00\n", TestCsvMapping.simple()))
                .hasMessage("line 2: every description column is empty");
    }

    // ------------------------------------------------------------------ records and tracing

    @Test
    void multiLineRecordsAreTracedFromTheirFirstLine() {
        String text = "Date,Description,Amount\r\n"
                + "2026-04-01,\"LINE ONE\r\nLINE TWO\",-1.00\r\n"
                + "\r\n"
                + "   \r\n"
                + "2026-04-02,NEXT,-2.00";

        List<ParsedTxn> txns = parse(text, TestCsvMapping.simple()).txns();

        assertThat(txns).extracting(ParsedTxn::sourceLineNumber).containsExactly(2, 6);
        assertThat(txns).extracting(ParsedTxn::sourceLineText)
                .containsExactly("2026-04-01,\"LINE ONE\r\nLINE TWO\",-1.00", "2026-04-02,NEXT,-2.00");
        assertThat(txns.getFirst().rawDescription()).isEqualTo("LINE ONE\r\nLINE TWO");
    }

    @Test
    void aQuoteInsideAnUnquotedCellIsLiteral() {
        ParsedTxn txn = parse(SIMPLE_HEADER + "2026-04-01,12\" PIZZA,-1.00\n2026-04-02,NEXT,-2.00\n", TestCsvMapping.simple())
                .txns().getFirst();

        assertThat(txn.rawDescription()).isEqualTo("12\" PIZZA");
        assertThat(txn.sourceLineText()).isEqualTo("2026-04-01,12\" PIZZA,-1.00");
    }

    static Stream<Arguments> malformedQuoting() {
        return Stream.of(
                Arguments.of("2026-04-01,\"CAFE\"X,-1.00\n2026-04-03,DELI,-1.00\n"),
                Arguments.of("2026-04-02,\"UNCLOSED,-1.00\n2026-04-03,DELI,-1.00\n"));
    }

    @ParameterizedTest
    @MethodSource("malformedQuoting")
    void malformedQuotingFailsAtTheRecordsFirstLine(String rows) {
        assertThatThrownBy(() -> parse(SIMPLE_HEADER + "2026-04-01,OK,-1.00\n\n" + rows, TestCsvMapping.simple()))
                .isInstanceOf(StatementParseException.class)
                .hasMessage("line 4: malformed quoting (an unclosed quote, or text after a closing quote)");
    }

    @Test
    void rowsWithTheWrongColumnCountFail() {
        assertThatThrownBy(() -> parse(SIMPLE_HEADER + "2026-04-01,CAFE,-1.00,EXTRA\n", TestCsvMapping.simple()))
                .hasMessage("line 2: has 4 columns; expected 3");
    }

    @Test
    void balanceColumnIsCarriedPerRowAndBlankMeansNone() {
        List<ParsedTxn> txns = parse(NORTHFIELD_HEADER
                + "04/01/2026,RENT,1850.00,,\"1,350.00\"\n04/02/2026,CAFE,4.50,,\n", TestCsvMapping.northfield()).txns();

        assertThat(txns).extracting(ParsedTxn::runningBalance).containsExactly(new BigDecimal("1350.00"), null);
        assertThatThrownBy(() -> parse(NORTHFIELD_HEADER + "04/01/2026,RENT,1850.00,,n/a\n", TestCsvMapping.northfield()))
                .hasMessage("line 2: column 5 (balance) is not an amount");
    }

    // ------------------------------------------------------------------ limits

    @Test
    void fiftyThousandRowsAreAcceptedAndOneMoreIsNot() {
        String rows = "2026-04-01,CAFE,-1.00\n".repeat(CsvStatementParser.MAX_ROWS);

        assertThat(parse(SIMPLE_HEADER + rows, TestCsvMapping.simple()).txns()).hasSize(50_000);
        assertThatThrownBy(() -> parse(SIMPLE_HEADER + rows + "2026-04-01,CAFE,-1.00\n", TestCsvMapping.simple()))
                .hasMessage("line 50002: the file has more than 50000 rows");
    }

    @Test
    void recordsOver64KbFail() {
        String atLimit = "2026-04-01,\"" + "A".repeat(CsvStatementParser.MAX_RECORD_CHARS - 19) + "\",-1.00";
        assertThat(atLimit).hasSize(CsvStatementParser.MAX_RECORD_CHARS);

        assertThat(parse(SIMPLE_HEADER + atLimit + "\n", TestCsvMapping.simple()).txns()).hasSize(1);
        assertThatThrownBy(() -> parse(SIMPLE_HEADER + atLimit.replace("\"A", "\"AA") + "\n", TestCsvMapping.simple()))
                .hasMessage("line 2: record is longer than 65536 characters");
    }

    // ------------------------------------------------------------------ SEC-10

    static Stream<Arguments> failingRowsWithMarker() {
        return Stream.of(
                Arguments.of(SIMPLE_HEADER + "2026-04-01," + MARKER + "," + MARKER + "\n", TestCsvMapping.simple()),
                Arguments.of(SIMPLE_HEADER + MARKER + "," + MARKER + ",-1.00\n", TestCsvMapping.simple()),
                Arguments.of(SIMPLE_HEADER + "2026-04-01," + MARKER + ",-1.00," + MARKER + "\n", TestCsvMapping.simple()),
                Arguments.of(SIMPLE_HEADER + "2026-04-01,\"" + MARKER + "\"" + MARKER + ",-1.00\n", TestCsvMapping.simple()),
                Arguments.of(SIMPLE_HEADER + "2026-04-01,\"" + MARKER + ",-1.00\n", TestCsvMapping.simple()),
                Arguments.of(NORTHFIELD_HEADER + "04/01/2026," + MARKER + ",1.00,2.00," + MARKER + "\n",
                        TestCsvMapping.northfield()),
                Arguments.of(NORTHFIELD_HEADER + "04/01/2026," + MARKER + ",1.00,,1" + MARKER + "\n",
                        TestCsvMapping.northfield()),
                Arguments.of(SIMPLE_HEADER + "2026-04-01," + MARKER + ",\"" + " ".repeat(70) + "1.00\"\n",
                        TestCsvMapping.simple()));
    }

    @ParameterizedTest
    @MethodSource("failingRowsWithMarker")
    void errorMessagesNeverQuoteCellValues(String text, TestCsvMapping mapping) {
        assertThatThrownBy(() -> parse(text, mapping))
                .isInstanceOf(StatementParseException.class)
                .satisfies(e -> assertThat(e.getMessage()).startsWith("line 2: ").doesNotContain(MARKER));
    }

    @Test
    void parsedRowsPrintNoContents() {
        ParsedStatement parsed = parse(SIMPLE_HEADER + "2026-04-01," + MARKER + ",-1.00\n", TestCsvMapping.simple());

        assertThat(parsed.toString()).doesNotContain(MARKER);
        assertThat(parsed.txns().getFirst().toString()).doesNotContain(MARKER);
    }

    // ------------------------------------------------------------------ helpers

    private static String northfieldRow(String debit, String credit) {
        return "04/01/2026,CAFE," + cell(debit) + "," + cell(credit) + ",100.00\n";
    }

    private static String cell(String value) {
        return value == null ? "" : "\"" + value + "\"";
    }

    private ParsedStatement parse(String text, TestCsvMapping mapping) {
        return parse(text, mapping, AccountType.CHECKING);
    }

    private ParsedStatement parse(String text, TestCsvMapping mapping, AccountType type) {
        return parser.parse(file(text), new ParseContext(type, mapping.build()));
    }

    private static ImportFile file(String text) {
        return ImportFile.of("statement.csv", bytes(text));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}

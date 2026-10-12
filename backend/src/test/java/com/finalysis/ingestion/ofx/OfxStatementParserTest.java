package com.finalysis.ingestion.ofx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.account.AccountType;
import com.finalysis.ingestion.AccountHints;
import com.finalysis.ingestion.FormatMatch;
import com.finalysis.ingestion.ImportFile;
import com.finalysis.ingestion.ParseContext;
import com.finalysis.ingestion.ParsedStatement;
import com.finalysis.ingestion.ParsedTxn;
import com.finalysis.ingestion.SourceSection;
import com.finalysis.ingestion.StatementParseException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class OfxStatementParserTest {

    private static final Path SAMPLE_DATA = Path.of("..", "sample-data");
    private static final ParseContext CHECKING = new ParseContext(AccountType.CHECKING);
    private static final String CHECKING_ACCOUNT_ID = "0047391001";
    private static final String SAVINGS_ACCOUNT_ID = "0047391002";
    private static final String CARD_ACCOUNT_ID = "4999000011112001";

    private final OfxStatementParser parser = new OfxStatementParser();

    // ------------------------------------------------------------------ sample data

    /** A row as the answer key has it: transaction date, description, amount. */
    record Row(LocalDate date, String description, BigDecimal amount) {}

    static Stream<Path> ofxSamples() throws IOException {
        try (Stream<Path> files = Files.list(SAMPLE_DATA.resolve("ofx"))) {
            return files.sorted().toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("ofxSamples")
    void sampleOfxFilesMatchTheAnswerKeyAndManifest(Path file) {
        String csv = "statements/" + file.getFileName().toString().replaceAll("\\.(ofx|qfx)$", ".csv");
        String[] manifest = csvRows(SAMPLE_DATA.resolve("statements-manifest.csv")).stream()
                .filter(c -> c[0].equals(csv)).findFirst().orElseThrow();
        List<Row> expected = csvRows(SAMPLE_DATA.resolve("answer-key.csv")).stream()
                .filter(c -> c[0].equals(csv))
                .map(c -> new Row(LocalDate.parse(c[2]), c[3], new BigDecimal(c[4])))
                .toList();

        ParsedStatement statement = parse(file);

        assertThat(statement.txns()).extracting(t -> new Row(t.txnDate(), t.rawDescription(), t.amount()))
                .containsExactlyElementsOf(expected);
        assertThat(statement.periodStart()).isEqualTo(LocalDate.parse(manifest[2]));
        assertThat(statement.periodEnd()).isEqualTo(LocalDate.parse(manifest[3]));
        assertThat(statement.openingBalance()).isNull();
        assertThat(statement.closingBalance()).isEqualTo(new BigDecimal(manifest[5]));
        assertThat(statement.txns().stream().map(ParsedTxn::amount).reduce(new BigDecimal(manifest[4]), BigDecimal::add))
                .isEqualTo(statement.closingBalance());
        assertThat(statement.warnings()).isEmpty();
    }

    @Test
    void sampleOfxFilesIncludeEmptyMemos() throws IOException {
        String april = Files.readString(SAMPLE_DATA.resolve("ofx/northfield-checking_2026-04.ofx"), StandardCharsets.ISO_8859_1);

        assertThat(april).contains("CHARSET:1252").contains("<MEMO>\r\n</STMTTRN>");
    }

    @Test
    void everyOfxSampleIsClaimedDefinitelyAndNothingElseIs() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SAMPLE_DATA)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }

        assertThat(files).anyMatch(f -> f.toString().endsWith(".qfx")).anyMatch(f -> f.toString().endsWith(".csv"));
        for (Path file : files) {
            boolean ofx = file.toString().matches(".*\\.(ofx|qfx)$");
            assertThat(parser.detect(ImportFile.of(file.getFileName().toString(), Files.readAllBytes(file))))
                    .as(file.toString()).isEqualTo(ofx ? FormatMatch.DEFINITE : FormatMatch.NONE);
        }
    }

    // ------------------------------------------------------------------ mapping

    @Nested
    class BankStatements {

        @Test
        void mapsStatementAndTransactionFields() {
            ParsedStatement statement = parse(SAMPLE_DATA.resolve("ofx/northfield-checking_2026-04.ofx"));
            ParsedTxn rent = statement.txns().getFirst();

            assertThat(statement.hints()).isEqualTo(new AccountHints("1001", AccountType.CHECKING, "USD"));
            assertThat(statement.sectionTotals()).isEmpty();
            assertThat(rent.printedDate()).isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(rent.txnDate()).isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(rent.postDate()).isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(rent.rawDescription()).isEqualTo("PARKSIDE PROPERTY MGMT RENT WEB PMT");
            assertThat(rent.printedAmount()).isEqualTo("-1850.00");
            assertThat(rent.amount()).isEqualTo(new BigDecimal("-1850.00"));
            assertThat(rent.section()).isEqualTo(SourceSection.WITHDRAWAL);
            assertThat(rent.externalId()).isEqualTo("NF2026040100013");
            assertThat(rent.checkNumber()).isNull();
            assertThat(rent.sourceLineNumber()).isEqualTo(43);
            assertThat(rent.sourceLineText()).startsWith("<STMTTRN>\r\n<TRNTYPE>DEBIT").endsWith("<MEMO>PMT\r\n</STMTTRN>");
            assertThat(statement.txns()).filteredOn(t -> t.amount().signum() > 0)
                    .extracting(ParsedTxn::section).containsOnly(SourceSection.DEPOSIT);
        }

        @Test
        void mapsChecksAndKeepsPrintedAmountsAsWritten() {
            ParsedStatement statement = parseText(bank("""
                    <STMTTRN>
                    <TRNTYPE>CHECK
                    <DTPOSTED>20260411
                    <TRNAMT>-75.5
                    <FITID>F1
                    <CHECKNUM>1042
                    <NAME>CHECK 1042
                    </STMTTRN>
                    <STMTTRN>
                    <TRNTYPE>CREDIT
                    <DTPOSTED>20260412
                    <TRNAMT>+20
                    <NAME>REFUND
                    </STMTTRN>
                    """));

            ParsedTxn check = statement.txns().get(0);
            assertThat(check.section()).isEqualTo(SourceSection.CHECK);
            assertThat(check.checkNumber()).isEqualTo("1042");
            assertThat(check.printedAmount()).isEqualTo("-75.5");
            assertThat(check.amount()).isEqualTo(new BigDecimal("-75.50"));
            assertThat(statement.txns().get(1).printedAmount()).isEqualTo("+20");
            assertThat(statement.txns().get(1).amount()).isEqualTo(new BigDecimal("20.00"));
            assertThat(statement.txns().get(1).externalId()).isNull();
        }

        @Test
        void readsOnlyTheDatePartOfDateTimes() {
            ParsedStatement statement = parseText(bank("""
                    <STMTTRN>
                    <TRNTYPE>DEBIT
                    <DTPOSTED>20260430235959.999[+14:LINT]
                    <DTUSER>20260429000000[-12:BIT]
                    <TRNAMT>-1.00
                    <NAME>LATE NIGHT
                    </STMTTRN>
                    """));

            ParsedTxn txn = statement.txns().getFirst();
            assertThat(txn.printedDate()).isEqualTo(LocalDate.of(2026, 4, 30));
            assertThat(txn.postDate()).isEqualTo(LocalDate.of(2026, 4, 30));
            assertThat(txn.txnDate()).isEqualTo(LocalDate.of(2026, 4, 29));
        }

        @Test
        void savingsAccountTypeIsMappedAndUnknownTypesWarn() {
            assertThat(parseText(bank("", "SAVINGS")).hints().type()).isEqualTo(AccountType.SAVINGS);

            ParsedStatement moneyMarket = parseText(bank("", "MONEYMRKT"));
            assertThat(moneyMarket.hints().type()).isNull();
            assertThat(moneyMarket.warnings()).containsExactly("line 29: ACCTTYPE MONEYMRKT is not mapped to an account type");
        }

        @Test
        void accountNumberWithoutFourTrailingDigitsGivesNoLastFour() {
            ParsedStatement statement = parseText(bank("").replace(CHECKING_ACCOUNT_ID, "ACCT-12X"));

            assertThat(statement.hints().lastFour()).isNull();
            assertThat(statement.warnings()).containsExactly("line 29: the account number does not end in four digits");
        }
    }

    @Nested
    class CardStatements {

        @Test
        void mapsCardStatementWithTransactionAndPostDates() {
            ParsedStatement statement = parse(SAMPLE_DATA.resolve("ofx/apex-visa_2026-05.qfx"));

            assertThat(statement.hints()).isEqualTo(new AccountHints("2001", AccountType.CREDIT_CARD, "USD"));
            assertThat(statement.txns()).anyMatch(t -> !t.txnDate().equals(t.printedDate()));
            assertThat(statement.txns()).allMatch(t -> t.postDate().equals(t.printedDate()));
            assertThat(statement.txns()).filteredOn(t -> t.amount().signum() < 0)
                    .extracting(ParsedTxn::section).containsOnly(SourceSection.PURCHASE);
            assertThat(statement.txns()).filteredOn(t -> t.amount().signum() > 0)
                    .isNotEmpty().extracting(ParsedTxn::section).containsOnly(SourceSection.PAYMENT);
            assertThat(statement.closingBalance()).isNegative();
            assertThat(statement.txns()).extracting(ParsedTxn::sourceLineText).noneMatch(text -> text.contains(CARD_ACCOUNT_ID));
        }
    }

    // ------------------------------------------------------------------ section table and sign warnings

    @ParameterizedTest(name = "card={0} {1} {2} -> {3}")
    @CsvSource({
            // bank
            "false, CHECK,     -75.00, CHECK",
            "false, CHECK,      75.00, CHECK",
            "false, FEE,      -12.00, FEE",
            "false, SRVCHG,    -3.00, FEE",
            "false, INT,        1.25, INTEREST",
            "false, DEBIT,    -10.00, WITHDRAWAL",
            "false, XFER,    -500.00, WITHDRAWAL",
            "false, CREDIT,    10.00, DEPOSIT",
            "false, DIRECTDEP, 10.00, DEPOSIT",
            "false, DEBIT,      0.00, DEPOSIT",
            "false, OTHER,     -1.00, WITHDRAWAL",
            "false, '',         1.00, DEPOSIT",
            // card
            "true,  FEE,      -35.00, FEE",
            "true,  SRVCHG,    -5.00, FEE",
            "true,  INT,      -18.40, INTEREST",
            "true,  CHECK,    -20.00, PURCHASE",
            "true,  DEBIT,    -66.43, PURCHASE",
            "true,  POS,      -12.00, PURCHASE",
            "true,  PAYMENT,  842.17, PAYMENT",
            "true,  CREDIT,    19.99, PAYMENT",
            "true,  DEBIT,      0.00, PAYMENT",
    })
    void sectionTableIsLocked(boolean card, String type, BigDecimal amount, SourceSection expected) {
        assertThat(OfxStatementParser.section(card, type, amount)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "card={0} {1} {2} contradicts={3}")
    @CsvSource({
            "false, DEBIT,      1.00, true",
            "false, CHECK,      1.00, true",
            "false, FEE,        1.00, true",
            "false, ATM,        1.00, true",
            "false, CREDIT,    -1.00, true",
            "false, DIRECTDEP, -1.00, true",
            "false, INT,       -1.00, true",
            "false, DEBIT,     -1.00, false",
            "false, CREDIT,     1.00, false",
            "false, DEBIT,      0.00, false",
            "false, XFER,       1.00, false",
            "false, PAYMENT,    1.00, false",
            "true,  INT,       -1.00, false",
            "true,  CREDIT,    -1.00, true",
            "true,  DEBIT,      1.00, true",
            "true,  PAYMENT,    1.00, false",
    })
    void signContradictionTable(boolean card, String type, BigDecimal amount, boolean contradicts) {
        assertThat(OfxStatementParser.contradicts(type, amount, card)).isEqualTo(contradicts);
    }

    @Test
    void contradictingTypesBecomeWarningsWithLineNumbersOnly() {
        ParsedStatement statement = parse(SAMPLE_DATA.resolve("edge-cases/ofx-trntype-contradicts-sign.ofx"));

        assertThat(statement.txns()).hasSize(3);
        assertThat(statement.warnings()).containsExactly(
                "line 43: TRNTYPE DEBIT with a positive amount",
                "line 51: TRNTYPE CREDIT with a negative amount");
    }

    // ------------------------------------------------------------------ descriptions

    @Nested
    class Descriptions {

        @Test
        void nameAndMemoAreJoinedWithOneSpace() {
            assertThat(description("<NAME>BLUE  BEAN\n<MEMO>CAFE 4412")).isEqualTo("BLUE  BEAN CAFE 4412");
        }

        @Test
        void nameAloneOrMemoAloneIsUsedWithoutAddedSpace() {
            assertThat(description("<NAME>NETFLIX.COM")).isEqualTo("NETFLIX.COM");
            assertThat(description("<NAME>NETFLIX.COM\n<MEMO>")).isEqualTo("NETFLIX.COM");
            assertThat(description("<MEMO>POS PURCHASE")).isEqualTo("POS PURCHASE");
            assertThat(description("<NAME>\n<MEMO>POS PURCHASE")).isEqualTo("POS PURCHASE");
        }

        @Test
        void memoRepeatingNameIsKept() {
            assertThat(description("<NAME>SHELL OIL\n<MEMO>SHELL OIL")).isEqualTo("SHELL OIL SHELL OIL");
        }

        @Test
        void payeeNameIsUsedWhenThereIsNoName() {
            assertThat(description("<PAYEE>\n<NAME>CITY WATER\n<ADDR1>1 MAIN ST\n</PAYEE>\n<MEMO>ACCT 7")).isEqualTo("CITY WATER ACCT 7");
        }

        @Test
        void rowWithNeitherNameNorMemoFailsTheFile() {
            assertThatThrownBy(() -> description("<NAME>\n<MEMO>"))
                    .isInstanceOf(StatementParseException.class)
                    .hasMessage("line 37: transaction has neither NAME nor MEMO");
        }

        private String description(String fields) {
            return parseText(bank("<STMTTRN>\n<TRNTYPE>DEBIT\n<DTPOSTED>20260410\n<TRNAMT>-1.00\n" + fields + "\n</STMTTRN>\n"))
                    .txns().getFirst().rawDescription();
        }
    }

    // ------------------------------------------------------------------ account numbers

    @Test
    void fullAccountNumbersNeverLeaveTheParser() throws Exception {
        ParsedStatement statement = parse(SAMPLE_DATA.resolve("edge-cases/ofx-bankacctto-transfer.ofx"));
        ParsedTxn transfer = statement.txns().getFirst();

        assertThat(transfer.sourceLineText()).contains("<ACCTID>****1002").contains("<BANKACCTTO>");
        List<String> strings = new ArrayList<>(List.of(statement.toString(), statement.hints().toString(), transfer.toString()));
        strings.addAll(statement.warnings());
        for (RecordComponent component : ParsedTxn.class.getRecordComponents()) {
            strings.add(String.valueOf(component.getAccessor().invoke(transfer)));
        }
        for (RecordComponent component : ParsedStatement.class.getRecordComponents()) {
            strings.add(String.valueOf(component.getAccessor().invoke(statement)));
        }
        assertThat(strings).noneMatch(s -> s.contains(CHECKING_ACCOUNT_ID) || s.contains(SAVINGS_ACCOUNT_ID));
    }

    @Test
    void maskingKeepsTheLastFourAndSurroundingWhitespace() {
        assertThat(OfxStatementParser.maskAccountIds("<CCACCTTO>\n  <ACCTID> 4999000011112001 </ACCTID>\n</CCACCTTO>"))
                .isEqualTo("<CCACCTTO>\n  <ACCTID> ****2001 </ACCTID>\n</CCACCTTO>");
        assertThat(OfxStatementParser.maskAccountIds("<ACCTID>123\n<ACCTID>98765")).isEqualTo("<ACCTID>****\n<ACCTID>****8765");
    }

    @Test
    void accountNumberIsNotInErrorMessages() {
        String twoStatements = bank("").replace("</BANKMSGSRSV1>", statementResponse("") + "</BANKMSGSRSV1>");

        assertThatThrownBy(() -> parseText(twoStatements.replace("<CURDEF>USD", "<CURDEF>EUR")))
                .isInstanceOf(StatementParseException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(CHECKING_ACCOUNT_ID));
        assertThatThrownBy(() -> parseText(bank("").replace("<DTSTART>20260401", "<DTSTART>" + CHECKING_ACCOUNT_ID)))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(CHECKING_ACCOUNT_ID));
    }

    // ------------------------------------------------------------------ errors

    @Nested
    class Errors {

        @Test
        void exactlyOneStatementPerFile() {
            String two = bank("").replace("</BANKMSGSRSV1>", statementResponse("") + "</BANKMSGSRSV1>");
            assertThatThrownBy(() -> parseText(two)).hasMessageContaining("more than one statement");

            String none = bank("").replaceAll("(?s)<BANKMSGSRSV1>.*</BANKMSGSRSV1>\n", "");
            assertThatThrownBy(() -> parseText(none)).hasMessageContaining("no bank or credit card statement");
        }

        @Test
        void onlyUsdIsSupported() {
            assertThatThrownBy(() -> parseText(bank("").replace("<CURDEF>USD", "<CURDEF>EUR")))
                    .isInstanceOf(StatementParseException.class)
                    .hasMessageContaining("only USD statements are supported, not EUR");
            assertThatThrownBy(() -> parseText(bank("").replace("<CURDEF>USD\n", "")))
                    .hasMessageContaining("<STMTRS> has no <CURDEF>");
        }

        @Test
        void institutionErrorStatusIsReported() {
            assertThatThrownBy(() -> parseText(bank("").replace("<TRNUID>1\n<STATUS>\n<CODE>0", "<TRNUID>1\n<STATUS>\n<CODE>2000")))
                    .hasMessageContaining("the institution reported error 2000");
        }

        @Test
        void invalidValuesFailWithLineAndTag() {
            String row = "<STMTTRN>\n<TRNTYPE>DEBIT\n<DTPOSTED>%s\n<TRNAMT>%s\n<NAME>X\n</STMTTRN>\n";

            assertThatThrownBy(() -> parseText(bank(row.formatted("20261340", "-1.00"))))
                    .hasMessage("line 37: invalid date in <DTPOSTED>");
            assertThatThrownBy(() -> parseText(bank(row.formatted("2026", "-1.00"))))
                    .hasMessage("line 37: invalid date in <DTPOSTED>");
            assertThatThrownBy(() -> parseText(bank(row.formatted("20260410", "-1,50"))))
                    .hasMessage("line 37: invalid amount in <TRNAMT>");
            assertThatThrownBy(() -> parseText(bank(row.formatted("20260410", "-1.005"))))
                    .hasMessage("line 37: invalid transaction: amount has more than two decimal places");
            assertThatThrownBy(() -> parseText(bank(row.formatted("20260410", ""))))
                    .hasMessage("line 37: <STMTTRN> has no <TRNAMT>");
        }

        @Test
        void missingStructureIsReported() {
            assertThatThrownBy(() -> parseText(bank("").replaceAll("(?s)<BANKTRANLIST>.*</BANKTRANLIST>\n", "")))
                    .hasMessageContaining("statement has no <BANKTRANLIST>");
            assertThatThrownBy(() -> parseText(bank("").replace("<DTEND>20260430\n", "")))
                    .hasMessageContaining("<BANKTRANLIST> has no <DTEND>");
            assertThatThrownBy(() -> parseText(bank("").replace("<DTSTART>20260401", "<DTSTART>20260501")))
                    .hasMessageContaining("invalid statement: periodEnd is before periodStart");
        }

        @Test
        void xxeSampleIsRejectedBeforeAnyEntityIsResolved() {
            assertThatThrownBy(() -> parse(SAMPLE_DATA.resolve("edge-cases/ofx-xxe.ofx")))
                    .isInstanceOf(StatementParseException.class)
                    .hasMessageContaining("DTD declarations");
        }
    }

    // ------------------------------------------------------------------ detection

    static Stream<Arguments> fileStarts() {
        return Stream.of(
                Arguments.of("OFXHEADER:100\nDATA:OFXSGML\n", FormatMatch.DEFINITE),
                Arguments.of("\r\n  OFXHEADER:100\n", FormatMatch.DEFINITE),
                Arguments.of("<?xml version=\"1.0\"?>\n<?OFX OFXHEADER=\"200\"?><OFX>", FormatMatch.DEFINITE),
                Arguments.of("<?OFX OFXHEADER=\"200\"?><OFX>", FormatMatch.DEFINITE),
                Arguments.of("<OFX><SIGNONMSGSRSV1>", FormatMatch.DEFINITE),
                Arguments.of("<?xml version=\"1.0\"?><invoice/>", FormatMatch.NONE),
                Arguments.of("Date,Description,Amount\n", FormatMatch.NONE),
                Arguments.of("%PDF-1.7\n", FormatMatch.NONE),
                Arguments.of("OFXHEADER 100", FormatMatch.NONE),
                Arguments.of("<ofx>", FormatMatch.NONE));
    }

    @ParameterizedTest
    @MethodSource("fileStarts")
    void detectsOfxByContent(String start, FormatMatch expected) {
        assertThat(parser.detect(ImportFile.of("statement.txt", start.getBytes(StandardCharsets.UTF_8)))).isEqualTo(expected);
    }

    @Test
    void detectionAllowsABomAndLooksForTheOfxDeclarationInTheFirstKilobyteOnly() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] sgml = "OFXHEADER:100\n".getBytes(StandardCharsets.US_ASCII);
        byte[] withBom = new byte[bom.length + sgml.length];
        System.arraycopy(bom, 0, withBom, 0, 3);
        System.arraycopy(sgml, 0, withBom, 3, sgml.length);
        String padding = " ".repeat(1000); // <?OFX starts at offset 1007, inside the 1 KB window

        assertThat(parser.detect(ImportFile.of("a.ofx", withBom))).isEqualTo(FormatMatch.DEFINITE);
        assertThat(parser.detect(ImportFile.of("a", ("<?xml?>" + padding + "<?OFX?>").getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(FormatMatch.DEFINITE);
        assertThat(parser.detect(ImportFile.of("a", ("<?xml?>" + padding + " ".repeat(20) + "<?OFX?>").getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(FormatMatch.NONE);
        assertThat(parser.detect(ImportFile.of("empty", new byte[0]))).isEqualTo(FormatMatch.NONE);
    }

    @Test
    void parserIdIsOfx() {
        assertThat(parser.id()).isEqualTo("ofx");
    }

    // ------------------------------------------------------------------ helpers

    private ParsedStatement parse(Path file) {
        try {
            return parser.parse(ImportFile.of(file.getFileName().toString(), Files.readAllBytes(file)), CHECKING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ParsedStatement parseText(String ofx) {
        return parser.parse(ImportFile.of("test.ofx", ofx.getBytes(StandardCharsets.UTF_8)), CHECKING);
    }

    /**
     * A minimal OFX 1.x checking statement. The header ends on line 10, OFX starts on line 11,
     * BANKACCTFROM on line 29 and the first STMTTRN in {@code transactions} on line 37.
     */
    private static String bank(String transactions) {
        return bank(transactions, "CHECKING");
    }

    private static String bank(String transactions, String accountType) {
        return """
                OFXHEADER:100
                DATA:OFXSGML
                VERSION:102
                SECURITY:NONE
                ENCODING:UTF-8
                CHARSET:NONE
                COMPRESSION:NONE
                OLDFILEUID:NONE
                NEWFILEUID:NONE

                <OFX>
                <SIGNONMSGSRSV1>
                <SONRS>
                <STATUS>
                <CODE>0
                <SEVERITY>INFO
                </STATUS>
                </SONRS>
                </SIGNONMSGSRSV1>
                <BANKMSGSRSV1>
                """ + statementResponse(transactions).replace("<ACCTTYPE>CHECKING", "<ACCTTYPE>" + accountType) + """
                </BANKMSGSRSV1>
                </OFX>
                """;
    }

    private static String statementResponse(String transactions) {
        return """
                <STMTTRNRS>
                <TRNUID>1
                <STATUS>
                <CODE>0
                <SEVERITY>INFO
                </STATUS>
                <STMTRS>
                <CURDEF>USD
                <BANKACCTFROM>
                <BANKID>999000111
                <ACCTID>%s
                <ACCTTYPE>CHECKING
                </BANKACCTFROM>
                <BANKTRANLIST>
                <DTSTART>20260401
                <DTEND>20260430
                %s</BANKTRANLIST>
                <LEDGERBAL>
                <BALAMT>100.00
                <DTASOF>20260430
                </LEDGERBAL>
                </STMTRS>
                </STMTTRNRS>
                """.formatted(CHECKING_ACCOUNT_ID, transactions);
    }

    private static List<String[]> csvRows(Path file) {
        try {
            return Files.readAllLines(file).stream().skip(1).map(line -> line.split(",", -1)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

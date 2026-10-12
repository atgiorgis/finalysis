package com.finalysis.ingestion.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.finalysis.account.AccountType;
import com.finalysis.ingestion.ImportFile;
import com.finalysis.ingestion.ParseContext;
import com.finalysis.ingestion.ParsedStatement;
import com.finalysis.ingestion.ParsedTxn;
import com.finalysis.ingestion.SourceSection;
import com.finalysis.ingestion.StatementParseException;
import com.finalysis.ingestion.TransactionFingerprint;
import com.finalysis.support.ImportTestSupport;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The CSV parser against the committed synthetic data: every monthly statement of the three sample
 * institutions, each read with a hand-written mapping, must give exactly the answer-key rows; the
 * generated {@code edge-cases/csv-*} files cover quoting, encodings, duplicates and ordering.
 */
class CsvSampleDataParseTest {

    private final CsvStatementParser parser = new CsvStatementParser();

    record ManifestEntry(String file, String account, LocalDate start, LocalDate end, BigDecimal opening) {
    }

    static Stream<ManifestEntry> sampleStatements() throws IOException {
        return Files.readAllLines(ImportTestSupport.SAMPLE_DATA.resolve("statements-manifest.csv")).stream().skip(1)
                .map(line -> line.split(",", -1))
                .filter(c -> c[0].startsWith("statements/"))
                .map(c -> new ManifestEntry(c[0], c[1], LocalDate.parse(c[2]), LocalDate.parse(c[3]), new BigDecimal(c[4])));
    }

    @Test
    void everySampleMonthIsCovered() throws IOException {
        assertThat(sampleStatements()).hasSize(25);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sampleStatements")
    void sampleStatementsParseToTheAnswerKey(ManifestEntry entry) throws IOException {
        ParsedStatement parsed = parse(entry.file(), mappingFor(entry.account()), typeOf(entry.account()));

        List<String[]> expected = ImportTestSupport.answerKey(entry.file());
        List<String> lines = Files.readAllLines(ImportTestSupport.SAMPLE_DATA.resolve(entry.file()));
        assertThat(parsed.txns()).hasSize(expected.size());
        BigDecimal balance = entry.opening();
        for (int i = 0; i < expected.size(); i++) {
            ParsedTxn txn = parsed.txns().get(i);
            String[] row = expected.get(i);
            BigDecimal amount = new BigDecimal(row[4]);
            assertThat(txn.txnDate()).as("row %d date", i).isEqualTo(LocalDate.parse(row[2]));
            assertThat(txn.rawDescription()).isEqualTo(row[3]);
            assertThat(txn.amount()).isEqualTo(amount);
            assertThat(txn.sourceLineNumber()).isEqualTo(i + 2);
            assertThat(txn.sourceLineText()).isEqualTo(lines.get(i + 1));
            assertThat(txn.section()).isEqualTo(CsvStatementParser.section(typeOf(entry.account()), amount, false));
            assertThat(txn.externalId()).isNull();
            assertThat(txn.checkNumber()).isNull();

            if (entry.account().equals("apex-visa")) {
                LocalDate posted = LocalDate.parse(lines.get(i + 1).split(",")[1]);
                assertThat(txn.postDate()).isEqualTo(posted);
                assertThat(txn.printedDate()).isEqualTo(posted);
            } else {
                assertThat(txn.postDate()).isNull();
                assertThat(txn.printedDate()).isEqualTo(txn.txnDate());
            }
            if (entry.account().startsWith("northfield")) {
                balance = balance.add(amount);
                assertThat(txn.runningBalance()).as("running balance on line %d", i + 2).isEqualTo(balance);
            } else {
                assertThat(txn.runningBalance()).isNull();
            }
        }
        assertThat(parsed.periodStart()).isAfterOrEqualTo(entry.start());
        assertThat(parsed.periodEnd()).isBeforeOrEqualTo(entry.end());
        assertThat(parsed.hints().currency()).isNull();
        assertThat(parsed.openingBalance()).isNull();
        assertThat(parsed.closingBalance()).isNull();
        assertThat(parsed.warnings()).isEmpty();
    }

    @Test
    void brokenStatementParsesBecauseOnlyReconciliationCanTellItIsIncomplete() {
        ParsedStatement parsed = parse("broken/summit-card_2026-06.csv", TestCsvMapping.summit(), AccountType.CREDIT_CARD);

        assertThat(parsed.txns()).hasSize(ImportTestSupport.answerKey("statements/summit-card_2026-06.csv").size() - 1);
    }

    @Test
    void quotedCellsKeepCommasQuotesLineBreaksSpacesAndNonAsciiText() {
        ParsedStatement parsed = parse("edge-cases/csv-quoted-fields.csv", TestCsvMapping.summit(), AccountType.CREDIT_CARD);

        assertThat(parsed.txns()).extracting(ParsedTxn::rawDescription).containsExactly(
                "BLUE BEAN CAFE, ARLINGTON VA",
                "NAKAMURA RAMEN\nTIP ADJUSTMENT",
                "THE \"GOOD\" DELI",
                "CAFÉ MÜNCHEN 東京",
                "  PADDED MERCHANT  ");
        // The BOM is gone (the header matched), and the multi-line record starts on line 3.
        assertThat(parsed.txns()).extracting(ParsedTxn::sourceLineNumber).containsExactly(2, 3, 5, 6, 7);
        assertThat(parsed.txns().get(1).sourceLineText()).isEqualTo("04-Apr-2026,\"NAKAMURA RAMEN\nTIP ADJUSTMENT\",-12.00");
        assertThat(parsed.txns().get(2).sourceLineText()).isEqualTo("05-Apr-2026,\"THE \"\"GOOD\"\" DELI\",-9.25");
    }

    @Test
    void formulaLeadingDescriptionsAreStoredUnchanged() {
        ParsedStatement parsed = parse("edge-cases/csv-formula-descriptions.csv", TestCsvMapping.summit(),
                AccountType.CREDIT_CARD);

        // SEC-11: no escaping on the way in; it belongs to any future CSV export.
        assertThat(parsed.txns()).extracting(ParsedTxn::rawDescription)
                .containsExactly("=SUM(A1:A9)", "+1 PREMIUM CAFE", "-CANCELLED ORDER", "@HANDLE COFFEE");
    }

    @Test
    void identicalSameDayRowsAreKeptAndNearDuplicatesStayDistinct() {
        ParsedStatement parsed = parse("edge-cases/csv-duplicates.csv", TestCsvMapping.summit(), AccountType.CREDIT_CARD);

        List<TransactionFingerprint.Input> inputs = parsed.txns().stream().map(t -> t.fingerprintInput(1L)).toList();
        assertThat(parsed.txns()).extracting(ParsedTxn::amount).containsExactly(
                new BigDecimal("-14.25"), new BigDecimal("-14.25"), new BigDecimal("-14.52"), new BigDecimal("-14.26"));
        assertThat(inputs.get(0)).isEqualTo(inputs.get(1));
        assertThat(inputs.subList(1, 4)).doesNotHaveDuplicates();
        assertThat(TransactionFingerprint.forStatement(inputs)).doesNotHaveDuplicates();
    }

    @Test
    void cardRefundIsMoneyInAndNotADuplicateOfThePurchase() {
        ParsedStatement parsed = parse("edge-cases/csv-card-refund.csv", TestCsvMapping.apex(), AccountType.CREDIT_CARD);

        assertThat(parsed.txns()).extracting(ParsedTxn::amount, ParsedTxn::printedAmount, ParsedTxn::section)
                .containsExactly(
                        tuple(new BigDecimal("-120.00"), "120.00", SourceSection.PURCHASE),
                        tuple(new BigDecimal("120.00"), "-120.00", SourceSection.PAYMENT));
        assertThat(parsed.txns().get(0).fingerprintInput(1L)).isNotEqualTo(parsed.txns().get(1).fingerprintInput(1L));
    }

    @Test
    void newestFirstFileKeepsFileOrderAndSpansItsDates() {
        ParsedStatement parsed = parse("edge-cases/csv-newest-first.csv", TestCsvMapping.summit(), AccountType.CREDIT_CARD);

        List<String> oldestFirst = new ArrayList<>(ImportTestSupport.answerKey("statements/summit-card_2026-04.csv")
                .stream().map(row -> row[3]).toList());
        Collections.reverse(oldestFirst);
        assertThat(parsed.txns()).extracting(ParsedTxn::rawDescription).containsExactlyElementsOf(oldestFirst);
        assertThat(parsed.txns().getFirst().printedDate()).isAfter(parsed.txns().getLast().printedDate());
        assertThat(parsed.periodStart()).isEqualTo(parsed.txns().getLast().printedDate());
        assertThat(parsed.periodEnd()).isEqualTo(parsed.txns().getFirst().printedDate());
    }

    @Test
    void windows1252FileReadsWithItsEncodingAndFailsAsUtf8() {
        TestCsvMapping mapping = TestCsvMapping.northfield();
        mapping.encoding = "windows-1252";

        ParsedStatement parsed = parse("edge-cases/csv-windows-1252.csv", mapping, AccountType.CHECKING);

        assertThat(parsed.txns()).extracting(ParsedTxn::rawDescription)
                .containsExactly("CAFÉ RÉSUMÉ ARLINGTON VA", "JOSÉ’S TACOS");
        assertThat(parsed.txns().getFirst().sourceLineText()).isEqualTo("04/05/2026,CAFÉ RÉSUMÉ ARLINGTON VA,12.75,,987.25");
        assertThatThrownBy(() -> parse("edge-cases/csv-windows-1252.csv", TestCsvMapping.northfield(), AccountType.CHECKING))
                .isInstanceOf(StatementParseException.class)
                .hasMessage("the file is not valid UTF-8 text");
    }

    @Test
    void overlappingExportHasTheAnswerKeyRowsOfItsDates() {
        ParsedStatement parsed = parse("edge-cases/csv-northfield-checking_2026-04-15_2026-05-14.csv",
                TestCsvMapping.northfield(), AccountType.CHECKING);

        LocalDate from = LocalDate.of(2026, 4, 15);
        LocalDate to = LocalDate.of(2026, 5, 14);
        List<String[]> expected = Stream.concat(
                        ImportTestSupport.answerKey("statements/northfield-checking_2026-04.csv").stream(),
                        ImportTestSupport.answerKey("statements/northfield-checking_2026-05.csv").stream())
                .filter(row -> !LocalDate.parse(row[2]).isBefore(from) && !LocalDate.parse(row[2]).isAfter(to))
                .toList();
        assertThat(parsed.txns()).extracting(ParsedTxn::rawDescription)
                .containsExactlyElementsOf(expected.stream().map(row -> row[3]).toList());
        assertThat(parsed.txns()).extracting(ParsedTxn::amount)
                .containsExactlyElementsOf(expected.stream().map(row -> new BigDecimal(row[4])).toList());
        assertThat(parsed.periodStart()).isEqualTo(from);
        assertThat(parsed.periodEnd()).isEqualTo(LocalDate.of(2026, 5, 12)); // the last row in range
        // The export's balances continue the April statement's.
        assertThat(parsed.txns().getLast().runningBalance()).isEqualByComparingTo(
                runningBalanceOn("statements/northfield-checking_2026-05.csv", "05/12/2026"));
    }

    // ------------------------------------------------------------------ helpers

    private ParsedStatement parse(String sampleFile, TestCsvMapping mapping, AccountType type) {
        ImportFile file = ImportFile.of(sampleFile, ImportTestSupport.sample(sampleFile));
        return parser.parse(file, new ParseContext(type, mapping.build()));
    }

    private static TestCsvMapping mappingFor(String account) {
        return switch (account) {
            case "northfield-checking", "northfield-savings" -> TestCsvMapping.northfield();
            case "apex-visa" -> TestCsvMapping.apex();
            case "summit-card" -> TestCsvMapping.summit();
            default -> throw new IllegalArgumentException(account);
        };
    }

    private static AccountType typeOf(String account) {
        return switch (account) {
            case "northfield-checking" -> AccountType.CHECKING;
            case "northfield-savings" -> AccountType.SAVINGS;
            default -> AccountType.CREDIT_CARD;
        };
    }

    private static BigDecimal runningBalanceOn(String file, String date) {
        String text = new String(ImportTestSupport.sample(file), StandardCharsets.UTF_8);
        return text.lines().filter(line -> line.startsWith(date)).map(line -> line.split(","))
                .map(c -> new BigDecimal(c[4])).reduce((first, second) -> second).orElseThrow();
    }
}

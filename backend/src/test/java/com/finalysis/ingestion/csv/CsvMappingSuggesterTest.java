package com.finalysis.ingestion.csv;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountType;
import com.finalysis.ingestion.ImportFile;
import com.finalysis.ingestion.csv.SuggestedCsvMapping.Confidence;
import com.finalysis.support.ImportTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Suggested mappings: the sample layouts through {@link CsvPreview#of} (detection included), and
 * synonym, date-format and sign rules on inline headers and rows.
 */
class CsvMappingSuggesterTest {

    private static final CsvTable COMMA_TABLE = new CsvTable(',', "UTF-8", 0);

    // ------------------------------------------------------------------ sample layouts

    @Test
    void northfieldIsDebitCreditWithABalance() {
        SuggestedCsvMapping suggested = suggestSample("statements/northfield-checking_2026-04.csv", AccountType.CHECKING);
        CsvMappingRequest m = suggested.mapping();

        assertThat(m.headers()).containsExactly("Date", "Description", "Debit", "Credit", "Balance");
        assertThat(m.dateColumn()).isEqualTo("Date");
        assertThat(m.postDateColumn()).isNull();
        assertThat(m.dateFormat()).isEqualTo("MM/dd/yyyy");
        assertThat(m.descriptionColumns()).containsExactly("Description");
        assertThat(m.amountMode()).isEqualTo(CsvAmountMode.DEBIT_CREDIT);
        assertThat(m.debitColumn()).isEqualTo("Debit");
        assertThat(m.creditColumn()).isEqualTo("Credit");
        assertThat(m.amountColumn()).isNull();
        assertThat(m.balanceColumn()).isEqualTo("Balance");
        assertThat(m.sign()).isEqualTo(CsvSign.AS_IS);
        assertThat(m.delimiter()).isEqualTo(",");
        assertThat(m.encoding()).isEqualTo("UTF-8");
        assertThat(m.headerRow()).isTrue();
        assertThat(m.skipRows()).isZero();
        assertThat(suggested.confidence()).containsExactly(
                Map.entry("dateColumn", Confidence.MATCHED),
                Map.entry("dateFormat", Confidence.MATCHED),
                Map.entry("descriptionColumns", Confidence.MATCHED),
                Map.entry("debitColumn", Confidence.MATCHED),
                Map.entry("creditColumn", Confidence.MATCHED),
                Map.entry("sign", Confidence.MATCHED),
                Map.entry("balanceColumn", Confidence.MATCHED));
        assertThat(suggested.notes()).isEmpty();
    }

    @Test
    void apexHasAPostDateAndPositivePurchases() {
        SuggestedCsvMapping suggested = suggestSample("statements/apex-visa_2026-05.csv", AccountType.CREDIT_CARD);
        CsvMappingRequest m = suggested.mapping();

        assertThat(m.dateColumn()).isEqualTo("Transaction Date");
        assertThat(m.postDateColumn()).isEqualTo("Post Date");
        assertThat(m.dateFormat()).isEqualTo("yyyy-MM-dd");
        assertThat(m.descriptionColumns()).containsExactly("Description");
        assertThat(m.amountMode()).isEqualTo(CsvAmountMode.SINGLE);
        assertThat(m.amountColumn()).isEqualTo("Amount");
        assertThat(m.sign()).isEqualTo(CsvSign.INVERTED);
        assertThat(suggested.confidence()).containsEntry("postDateColumn", Confidence.MATCHED)
                .containsEntry("sign", Confidence.GUESSED)
                .doesNotContainValue(Confidence.MISSING);
        assertThat(suggested.notes()).containsExactly("sign: 17 of 18 amounts are positive, so purchases look positive (INVERTED)");
    }

    @Test
    void summitHasPunctuatedHeadersAndNegativePurchases() {
        SuggestedCsvMapping suggested = suggestSample("statements/summit-card_2026-04.csv", AccountType.CREDIT_CARD);
        CsvMappingRequest m = suggested.mapping();

        assertThat(m.dateColumn()).isEqualTo("Trans. Date");
        assertThat(m.dateFormat()).isEqualTo("dd-MMM-yyyy");
        assertThat(m.descriptionColumns()).containsExactly("Merchant Name");
        assertThat(m.amountColumn()).isEqualTo("Amount");
        assertThat(m.sign()).isEqualTo(CsvSign.AS_IS);
        assertThat(suggested.confidence()).doesNotContainValue(Confidence.MISSING);
    }

    @Test
    void preambleFileSuggestsSkippingItsSummaryLines() {
        CsvPreview preview = previewSample("edge-cases/csv-preamble.csv", AccountType.CHECKING);

        assertThat(preview.skipRows()).isEqualTo(5);
        assertThat(preview.headers()).containsExactly("Date", "Description", "Debit", "Credit", "Balance");
        assertThat(preview.suggestedMapping().mapping().skipRows()).isEqualTo(5);
        assertThat(preview.suggestedMapping().confidence()).doesNotContainValue(Confidence.MISSING);
        assertThat(preview.suggestedMapping().notes())
                .containsExactly("skipRows: the table starts on line 6; the lines above it are skipped");
    }

    @Test
    void previewShowsTheFirstTenRowsButGuessesFromAllOfThem() {
        CsvPreview preview = previewSample("statements/apex-visa_2026-05.csv", AccountType.CREDIT_CARD);

        assertThat(preview.rows()).hasSize(CsvPreview.ROWS);
        assertThat(preview.rows().getFirst()).hasSize(5);
        assertThat(preview.mappingStatus()).isEqualTo(CsvPreview.MappingStatus.NONE);
        // 18 amounts counted, though only 10 rows are shown.
        assertThat(preview.suggestedMapping().notes().getFirst()).contains("of 18 amounts");
    }

    @Test
    void windows1252FileIsPreviewedInItsEncoding() {
        CsvPreview preview = previewSample("edge-cases/csv-windows-1252.csv", AccountType.CHECKING);

        assertThat(preview.encoding()).isEqualTo("windows-1252");
        assertThat(preview.suggestedMapping().mapping().encoding()).isEqualTo("windows-1252");
        assertThat(preview.rows().getFirst().get(1)).isEqualTo("CAFÉ RÉSUMÉ ARLINGTON VA");
    }

    @Test
    void savedMappingStatusComesFromReadingTheFileWithIt() {
        ImportFile file = sample("statements/apex-visa_2026-05.csv");

        CsvPreview matches = CsvPreview.of(file, AccountType.CREDIT_CARD, TestCsvMapping.apex().build());
        CsvPreview mismatch = CsvPreview.of(file, AccountType.CREDIT_CARD, TestCsvMapping.summit().build());

        assertThat(matches.mappingStatus()).isEqualTo(CsvPreview.MappingStatus.MATCHES);
        assertThat(matches.suggestedMapping()).isNull();
        assertThat(mismatch.mappingStatus()).isEqualTo(CsvPreview.MappingStatus.MISMATCH);
        assertThat(mismatch.suggestedMapping().mapping().dateColumn()).isEqualTo("Transaction Date");
        assertThat(matches.toString()).doesNotContain("Transaction Date").doesNotContain("AMZN");
    }

    // ------------------------------------------------------------------ header synonyms

    @Test
    void unknownHeadersAreMissing() {
        SuggestedCsvMapping suggested = suggest(List.of("Foo", "Bar", "Baz"), List.of(List.of("1", "2", "3")),
                AccountType.CREDIT_CARD);

        assertThat(suggested.confidence()).containsExactly(
                Map.entry("dateColumn", Confidence.MISSING),
                Map.entry("dateFormat", Confidence.MISSING),
                Map.entry("descriptionColumns", Confidence.MISSING),
                Map.entry("amountColumn", Confidence.MISSING),
                Map.entry("sign", Confidence.MISSING));
        assertThat(suggested.mapping().descriptionColumns()).isEmpty();
        assertThat(suggested.mapping().amountMode()).isNull();
        assertThat(suggested.mapping().sign()).isNull();
    }

    @Test
    void synonymsIgnoreCasePunctuationAndSpacing() {
        List<String> headers = List.of(" POSTING-DATE ", "Transaction  Date", "Payee Name", "Money Out", "Money In",
                "Running Balance", "Check No.");
        SuggestedCsvMapping suggested = suggest(headers,
                List.of(List.of("04/02/2026", "04/01/2026", "CAFE", "4.50", "", "95.50", "")), AccountType.CHECKING);
        CsvMappingRequest m = suggested.mapping();

        assertThat(m.dateColumn()).isEqualTo("Transaction  Date");
        assertThat(m.postDateColumn()).isEqualTo("POSTING-DATE");
        assertThat(m.descriptionColumns()).containsExactly("Payee Name");
        assertThat(m.debitColumn()).isEqualTo("Money Out");
        assertThat(m.creditColumn()).isEqualTo("Money In");
        assertThat(m.balanceColumn()).isEqualTo("Running Balance");
        assertThat(m.checkNumberColumn()).isEqualTo("Check No.");
        assertThat(suggested.confidence()).doesNotContainValue(Confidence.MISSING);
    }

    @Test
    void aLonePostDateIsTheDateColumn() {
        CsvMappingRequest m = suggest(List.of("Posted Date", "Memo", "Amount"),
                List.of(List.of("2026-04-01", "CAFE", "-4.50")), AccountType.CHECKING).mapping();

        assertThat(m.dateColumn()).isEqualTo("Posted Date");
        assertThat(m.postDateColumn()).isNull();
    }

    @Test
    void theEarliestDescriptionSynonymWinsAndOthersAreNoted() {
        SuggestedCsvMapping suggested = suggest(List.of("Date", "Memo", "Description", "Name", "Amount"),
                List.of(List.of("2026-04-01", "REF 1", "CAFE", "SQ", "-4.50")), AccountType.CHECKING);

        assertThat(suggested.mapping().descriptionColumns()).containsExactly("Description");
        assertThat(suggested.notes()).containsExactly("descriptionColumns: also description-like, not included: Memo, Name");
    }

    @Test
    void aSingleAmountColumnWinsOverDebitAndCredit() {
        CsvMappingRequest m = suggest(List.of("Date", "Description", "Amount", "Debit", "Credit"),
                List.of(List.of("2026-04-01", "CAFE", "-4.50", "4.50", "")), AccountType.CHECKING).mapping();

        assertThat(m.amountMode()).isEqualTo(CsvAmountMode.SINGLE);
        assertThat(m.amountColumn()).isEqualTo("Amount");
        assertThat(m.debitColumn()).isNull();
        assertThat(m.creditColumn()).isNull();
    }

    @Test
    void aHalfDebitCreditPairReportsTheMissingSide() {
        SuggestedCsvMapping suggested = suggest(List.of("Date", "Description", "Withdrawals"),
                List.of(List.of("2026-04-01", "CAFE", "4.50")), AccountType.CHECKING);

        assertThat(suggested.confidence()).containsEntry("debitColumn", Confidence.MATCHED)
                .containsEntry("creditColumn", Confidence.MISSING);
    }

    @Test
    void duplicateHeaderNamesAreNeverSuggested() {
        SuggestedCsvMapping suggested = suggest(List.of("Date", "Description", "Description", "Amount"),
                List.of(List.of("2026-04-01", "CAFE", "SQ", "-4.50")), AccountType.CHECKING);

        assertThat(suggested.confidence()).containsEntry("descriptionColumns", Confidence.MISSING);
    }

    // ------------------------------------------------------------------ date formats

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            04/25/2026 | MM/dd/yyyy
            4/25/2026  | M/d/yyyy
            2026-04-25 | yyyy-MM-dd
            25-Apr-2026| dd-MMM-yyyy
            04/25/26   | MM/dd/yy
            25/04/2026 | dd/MM/yyyy
            """)
    void unambiguousDatesPickTheirFormat(String date, String format) {
        SuggestedCsvMapping suggested = suggestWithDates(date);

        assertThat(suggested.mapping().dateFormat()).isEqualTo(format);
        assertThat(suggested.confidence()).containsEntry("dateFormat", Confidence.MATCHED);
        assertThat(suggested.notes()).isEmpty();
    }

    @Test
    void dayAndMonthBothUpToTwelveIsAmbiguousAndPicksTheUsFormat() {
        SuggestedCsvMapping suggested = suggestWithDates("04/05/2026", "03/07/2026");

        assertThat(suggested.mapping().dateFormat()).isEqualTo("MM/dd/yyyy");
        assertThat(suggested.confidence()).containsEntry("dateFormat", Confidence.GUESSED);
        assertThat(suggested.notes()).containsExactly(
                "dateFormat: ambiguous date format: the dates also read as dd/MM/yyyy, giving different dates; "
                        + "MM/dd/yyyy (US) was chosen");
    }

    @Test
    void oneDayAboveTwelveSettlesIt() {
        assertThat(suggestWithDates("04/05/2026", "04/13/2026").notes()).isEmpty();
        assertThat(suggestWithDates("05/04/2026", "13/04/2026").mapping().dateFormat()).isEqualTo("dd/MM/yyyy");
    }

    @Test
    void aDateNoFormatReadsIsMissing() {
        SuggestedCsvMapping suggested = suggestWithDates("2026-04-01", "April 2, 2026");

        assertThat(suggested.mapping().dateFormat()).isNull();
        assertThat(suggested.confidence()).containsEntry("dateFormat", Confidence.MISSING);
        assertThat(suggested.notes()).singleElement().asString().startsWith("dateFormat: none of MM/dd/yyyy");
    }

    @Test
    void postDatesCountAsEvidenceToo() {
        SuggestedCsvMapping suggested = suggest(List.of("Transaction Date", "Post Date", "Description", "Amount"),
                List.of(List.of("04/05/2026", "04/13/2026", "CAFE", "-4.50")), AccountType.CHECKING);

        assertThat(suggested.mapping().dateFormat()).isEqualTo("MM/dd/yyyy");
        assertThat(suggested.notes()).isEmpty();
    }

    // ------------------------------------------------------------------ sign

    @Test
    void signFollowsTheMajorityOnlyForCardsWithOneAmountColumn() {
        List<List<String>> mostlyPositive = List.of(row("12.00"), row("8.00"), row("-20.00"));

        assertThat(suggest(SIMPLE, mostlyPositive, AccountType.CREDIT_CARD).mapping().sign()).isEqualTo(CsvSign.INVERTED);
        assertThat(suggest(SIMPLE, List.of(row("-12.00"), row("20.00")), AccountType.CREDIT_CARD).mapping().sign())
                .isEqualTo(CsvSign.AS_IS);
        SuggestedCsvMapping checking = suggest(SIMPLE, mostlyPositive, AccountType.CHECKING);
        assertThat(checking.mapping().sign()).isEqualTo(CsvSign.AS_IS);
        assertThat(checking.confidence()).containsEntry("sign", Confidence.GUESSED);
        SuggestedCsvMapping debitCreditCard = suggest(List.of("Date", "Description", "Debit", "Credit"),
                List.of(List.of("2026-04-01", "CAFE", "4.50", "")), AccountType.CREDIT_CARD);
        assertThat(debitCreditCard.mapping().sign()).isEqualTo(CsvSign.AS_IS);
        assertThat(debitCreditCard.confidence()).containsEntry("sign", Confidence.MATCHED);
    }

    @Test
    void normalizeDropsCasePunctuationAndExtraSpaces() {
        assertThat(CsvMappingSuggester.normalize("  Trans. Date ")).isEqualTo("trans date");
        assertThat(CsvMappingSuggester.normalize("Check #")).isEqualTo("check");
        assertThat(CsvMappingSuggester.normalize("MONEY_OUT")).isEqualTo("money out");
    }

    // ------------------------------------------------------------------ helpers

    private static final List<String> SIMPLE = List.of("Date", "Description", "Amount");

    private static List<String> row(String amount) {
        return List.of("2026-04-01", "CAFE", amount);
    }

    private static SuggestedCsvMapping suggestWithDates(String... dates) {
        return suggest(SIMPLE, List.of(dates).stream().map(date -> List.of(date, "CAFE", "-4.50")).toList(),
                AccountType.CHECKING);
    }

    private static SuggestedCsvMapping suggest(List<String> headers, List<List<String>> rows, AccountType type) {
        return CsvMappingSuggester.suggest(headers, rows, COMMA_TABLE, type);
    }

    private static SuggestedCsvMapping suggestSample(String path, AccountType type) {
        return previewSample(path, type).suggestedMapping();
    }

    private static CsvPreview previewSample(String path, AccountType type) {
        return CsvPreview.of(sample(path), type, null);
    }

    private static ImportFile sample(String path) {
        return ImportFile.of(path, ImportTestSupport.sample(path));
    }
}

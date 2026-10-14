package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.categorize.MerchantCleaner.CleanedMerchant;
import com.finalysis.support.ImportTestSupport;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

/** Merchant cleaning with the shipped rules (docs/plans/phase-4.md §2). Pure Java, no Spring. */
class MerchantCleanerTest {

    private static final MerchantCleaner CLEANER = new MerchantCleaner(MerchantCleaningRules.loadDefault());
    private static final Path GOLDEN = Path.of("src/test/resources/categorize/answer-key-merchants.txt");

    /** The table of §2.3, both columns as planned. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            SQ *BLUE BEAN CAFE 4412                              | BLUE BEAN CAFE              | -
            TST* NAKAMURA RAMEN                                  | NAKAMURA RAMEN              | -
            AMZN MKTP US*7ETP43                                  | AMZN MKTP US                | -
            TRADER JOE S #662 ARLINGTON VA                       | TRADER JOE S                | -
            SHELL OIL 57442 ARLINGTON VA                         | SHELL OIL                   | -
            BEST BUY 00012345 ARLINGTON VA                       | BEST BUY                    | -
            CVS/PHARMACY #04512 ARLINGTON VA                     | CVS/PHARMACY                | -
            CHIPOTLE 2291                                        | CHIPOTLE                    | -
            DOORDASH*THAI BASIL                                  | DOORDASH THAI BASIL         | -
            GOOGLE *Google One                                   | GOOGLE GOOGLE ONE           | -
            ONLINE TRANSFER TO SAVINGS XXXXXX1002                | ONLINE TRANSFER TO SAVINGS  | -
            BRIGHTWAY LOGISTICS PAYROLL PPD ID: 4419920113       | BRIGHTWAY LOGISTICS PAYROLL | -
            ZELLE PAYMENT TO JANE Q SAMPLE CONF# 8XK2P1          | ZELLE                       | JANE Q SAMPLE
            ACH DEBIT ACME INS CO ID:9900112233 INDN:SAMPLE JANE | ACH DEBIT ACME INS          | -
            PAYPAL *NOVA BOOKS JANE.SAMPLE@EXAMPLE.COM          | NOVA BOOKS                  | -
            =HYPERLINK("x")                                      | HYPERLINK X                 | -
            #12345 0099                                          | -                           | -
            """)
    void cleansThePlannedExamples(String raw, String merchant, String counterparty) {
        assertThat(CLEANER.clean(raw)).isEqualTo(new CleanedMerchant(merchant, counterparty));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            ZELLE PAYMENT FROM ALEX R EXAMPLE CONF# 3MZ8Q0  | ZELLE      | ALEX R EXAMPLE
            ZELLE TRANSFER TO O'NEIL-SAMPLE                 | ZELLE      | O'NEIL-SAMPLE
            ZELLE TO JANE SAMPLE                            | ZELLE      | JANE SAMPLE
            ZELLE PAYMENT TO JANE SAMPLE 2                  | ZELLE      | -
            ZELLE 8XK2P1 J SAMPLE                           | ZELLE      | -
            CASH APP*JANE SAMPLE                            | CASH APP   | JANE SAMPLE
            CASH APP*JANE SAMPLE*SAMPLETOWN                 | CASH APP   | JANE SAMPLE
            CASH APP TRANSFER 4400112233                    | CASH APP   | -
            VENMO PAYMENT 1023456789 WEB ID: 3264681992     | VENMO      | -
            zelle payment to jane sample                    | ZELLE      | JANE SAMPLE
            """)
    void personToPersonNamesGoToCounterpartyAndNeverToTheMerchant(String raw, String merchant, String counterparty) {
        assertThat(CLEANER.clean(raw)).isEqualTo(new CleanedMerchant(merchant, counterparty));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            CHECKCARD 0504 NORTHGATE FUEL #88 SAMPLETOWN VA | NORTHGATE FUEL
            POS DEBIT SQ *CORNER BEAN CAFE 0412             | CORNER BEAN CAFE
            PURCHASE AUTHORIZED ON 05/04 MAPLE ST DELI      | MAPLE ST DELI
            PAYPAL *SAMPLESHOP                              | SAMPLESHOP
            AMZN MKTP US*QKLLFB                             | AMZN MKTP US
            AMAZON.COM*2K4L91 AMZN.COM/BILL                 | AMAZON.COM AMZN.COM/BILL
            7-ELEVEN 12345 DALLAS TX                        | 7-ELEVEN
            STARBUCKS ARLINGTON VA                          | STARBUCKS ARLINGTON VA
            #123 ARLINGTON VA                               | ARLINGTON VA
            21ST CENTURY SAMPLE                             | 21ST CENTURY SAMPLE
            99 CENTS ONLY                                   | 99 CENTS ONLY
            ACME 800-555-0199                               | ACME
            CARD ****1001 PAYMENT                           | CARD PAYMENT
            TRANSFER TO ...1002                             | TRANSFER TO
            ACME CONF # 77Q1                                | ACME
            IDAHO SAMPLE CO                                 | IDAHO SAMPLE CO
            WEBSTER BOOKS                                   | WEBSTER BOOKS
            ONLINE PAYMENT - THANK YOU                      | ONLINE PAYMENT THANK YOU
            '  -ACME-  '                                    | ACME
            ***                                             | -
            """)
    void stripsIdentifiersPrefixesAndLocationsGenerically(String raw, String merchant) {
        assertThat(CLEANER.clean(raw).merchant()).isEqualTo(merchant);
    }

    /** Every distinct description in the answer key, against a reviewed golden file. */
    @Test
    void cleansEveryAnswerKeyDescriptionAsTheGoldenFileSays() throws IOException {
        Map<String, String> golden = golden();
        Set<String> descriptions = new TreeSet<>();
        try (Reader reader = Files.newBufferedReader(ImportTestSupport.SAMPLE_DATA.resolve("answer-key.csv"))) {
            CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)
                    .forEach(record -> descriptions.add(record.get("description")));
        }

        assertThat(golden.keySet()).as("the golden file lists every answer-key description").isEqualTo(descriptions);
        golden.forEach((raw, merchant) -> assertThat(CLEANER.clean(raw))
                .as(raw).isEqualTo(new CleanedMerchant(merchant, null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyKnownDescription")
    void cleaningIsIdempotent(String raw) {
        CleanedMerchant once = CLEANER.clean(raw);
        if (once.merchant() != null) {
            assertThat(CLEANER.clean(once.merchant()).merchant()).isEqualTo(once.merchant());
        }
        assertThat(CLEANER.clean(raw)).isEqualTo(once);
    }

    static Stream<Arguments> everyKnownDescription() throws IOException {
        return Stream.concat(golden().keySet().stream(), Stream.of(
                        "ZELLE PAYMENT TO JANE Q SAMPLE CONF# 8XK2P1", "ACH DEBIT ACME INS CO ID:9900112233 INDN:SAMPLE JANE",
                        "=HYPERLINK(\"x\")", "CASH APP*JANE SAMPLE", "MAPLEWOOD GROCERY\n#214 SAMPLETOWN VA"))
                .map(Arguments::of);
    }

    @Test
    void wrappedLinesAreJoinedWithASpace() {
        assertThat(CLEANER.clean("MAPLEWOOD GROCERY\n#214 SAMPLETOWN VA").merchant()).isEqualTo("MAPLEWOOD GROCERY");
        assertThat(CLEANER.clean("MAPLE\r\nST\tDELI").merchant()).isEqualTo("MAPLE ST DELI");
    }

    @Test
    void foldsCompatibilityFormsAndAccentsAndDropsFormatCharacters() {
        assertThat(CLEANER.clean("CAFÉ LUMIÈRE").merchant()).isEqualTo("CAFE LUMIERE");
        assertThat(CLEANER.clean("ＡＣＭＥ ＤＥＬＩ").merchant()).isEqualTo("ACME DELI"); // fullwidth
        assertThat(CLEANER.clean("‮ACME‬ DELI​").merchant()).isEqualTo("ACME DELI"); // bidi, zero width
        assertThat(CLEANER.clean("TRADER JOE’S").merchant()).isEqualTo("TRADER JOE'S");
        assertThat(CLEANER.clean("\u0000ACME\u0007").merchant()).isEqualTo("ACME");
    }

    @Test
    void cutsAt64CharactersOnAWordBoundary() {
        String merchant = CLEANER.clean(
                "SUNRISE COMMUNITY HEALTH AND WELLNESS CENTER OUTPATIENT PHYSICAL THERAPY SERVICES").merchant();

        assertThat(merchant).isEqualTo("SUNRISE COMMUNITY HEALTH AND WELLNESS CENTER OUTPATIENT PHYSICAL").hasSizeLessThanOrEqualTo(64);
        assertThat(CLEANER.clean("A".repeat(80)).merchant()).isEqualTo("A".repeat(64));
        assertThat(CLEANER.clean("ACME " + "B".repeat(70)).merchant()).isEqualTo("ACME");
    }

    @Test
    void longInputIsBoundedBeforeAnyPatternRuns() {
        String raw = "ACME DELI " + "X ".repeat(100_000);

        assertThat(CLEANER.clean(raw).merchant()).startsWith("ACME DELI").hasSizeLessThanOrEqualTo(64);
        assertThat(MerchantCleaner.normalize(raw)).hasSize(MerchantCleaner.MAX_INPUT);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void nothingGivesNothing(String raw) {
        assertThat(CLEANER.clean(raw)).isEqualTo(new CleanedMerchant(null, null));
        assertThat(CLEANER.clean("   \n ")).isEqualTo(new CleanedMerchant(null, null));
    }

    @Test
    void counterpartyIsCappedAt100Characters() {
        String name = "JANE " + "Q".repeat(150);

        assertThat(CLEANER.clean("ZELLE PAYMENT TO " + name).counterparty()).hasSizeLessThanOrEqualTo(100);
    }

    /** The YAML drives the result: with no rules, only generic steps run. */
    @Test
    void withoutRulesOnlyTheGenericStepsRun() {
        MerchantCleaner bare = new MerchantCleaner(MerchantCleaningRules.load("version: 1\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(bare.clean("SQ *BLUE BEAN CAFE 4412").merchant()).isEqualTo("SQ BLUE BEAN CAFE");
        assertThat(bare.clean("ZELLE PAYMENT TO JANE SAMPLE")).isEqualTo(new CleanedMerchant("ZELLE PAYMENT TO JANE SAMPLE", null));
        assertThat(bare.clean("SHELL OIL 57442 ARLINGTON VA").merchant()).isEqualTo("SHELL OIL ARLINGTON VA");
        assertThat(bare.clean("TRANSFER TO SAVINGS XXXXXX1002").merchant()).isEqualTo("TRANSFER TO SAVINGS");
    }

    static Map<String, String> golden() throws IOException {
        Map<String, String> golden = new LinkedHashMap<>();
        try (Reader reader = Files.newBufferedReader(GOLDEN)) {
            for (CSVRecord record : CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
                golden.put(record.get("description"), record.get("merchant"));
            }
        }
        return golden;
    }
}

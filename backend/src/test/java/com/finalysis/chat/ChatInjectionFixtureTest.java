package com.finalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.support.ImportTestSupport;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

/**
 * The chat evaluation's injection fixture ({@code sample-data/chat/injection/}, docs/plans/phase-6.md §12.5, §13.1),
 * written by {@code tools/SampleDataGenerator.java}: the key covers every statement row once, the statement
 * reconciles with its manifest, the hostile descriptions are there in the forms the plan's defences expect, and the
 * canary 999.99 is in descriptions only, never an amount.
 */
class ChatInjectionFixtureTest {

    static final Path DIR = ImportTestSupport.SAMPLE_DATA.resolve("chat/injection");
    static final String CANARY = "999.99";

    @Test
    void theKeyCoversEveryStatementRowOnce() throws IOException {
        List<CSVRecord> statement = csv(DIR.resolve("injection-checking_2026-03.csv"));
        List<CSVRecord> key = csv(DIR.resolve("categories.csv"));

        assertThat(key).hasSameSizeAs(statement);
        for (int i = 0; i < statement.size(); i++) {
            CSVRecord row = statement.get(i);
            CSVRecord k = key.get(i);
            assertThat(k.get("line")).isEqualTo(String.valueOf(i + 2));
            assertThat(k.get("description")).isEqualTo(row.get("Description"));
            assertThat(new BigDecimal(k.get("amount"))).isEqualByComparingTo(signed(row));
            assertThat(k.get("expected_bucket")).isIn("INCOME", "EXPENSE", "UNCATEGORIZED");
            assertThat(k.get("category").isEmpty()).isEqualTo(k.get("expected_bucket").equals("UNCATEGORIZED"));
        }
    }

    @Test
    void theStatementReconcilesWithItsManifest() throws IOException {
        CSVRecord manifest = csv(DIR.resolve("manifest.csv")).getFirst();
        BigDecimal balance = new BigDecimal(manifest.get("opening_balance"));
        for (CSVRecord row : csv(DIR.resolve("injection-checking_2026-03.csv"))) {
            balance = balance.add(signed(row));
            assertThat(new BigDecimal(row.get("Balance"))).isEqualByComparingTo(balance);
        }
        assertThat(balance).isEqualByComparingTo(manifest.get("closing_balance"));
    }

    @Test
    void theHostileDescriptionsAreThereAndTheCanaryIsNeverAnAmount() throws IOException {
        List<CSVRecord> key = csv(DIR.resolve("categories.csv"));
        List<String> descriptions = key.stream().map(r -> r.get("description")).toList();

        assertThat(descriptions).filteredOn(d -> d.contains(CANARY)).hasSize(3);
        assertThat(descriptions).anyMatch(d -> d.contains("<") && d.contains("{")); // for the guard
        assertThat(descriptions).anyMatch(d -> d.matches(".*\\d{5,}.*"));           // for the redactor
        assertThat(key).noneMatch(r -> new BigDecimal(r.get("amount")).abs().compareTo(new BigDecimal(CANARY)) == 0);
    }

    private static BigDecimal signed(CSVRecord row) {
        return row.get("Debit").isEmpty() ? new BigDecimal(row.get("Credit")) : new BigDecimal(row.get("Debit")).negate();
    }

    private static List<CSVRecord> csv(Path file) throws IOException {
        return CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get()
                .parse(Files.newBufferedReader(file, StandardCharsets.UTF_8)).getRecords();
    }
}

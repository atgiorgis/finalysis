package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.analytics.AnalyticsFixture.Entry;
import com.finalysis.analytics.AnalyticsFixture.Statement;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The Phase 5 fixture's files agree with each other (docs/plans/phase-5.md §9.1): the key covers every statement row
 * once, the manifest's balances fit the rows, and the expected files are well-formed and refer to real rows.
 *
 * <p>The expected totals and alerts are worked out by hand and are deliberately <em>not</em> recomputed here: a test
 * that derived them from the rows would only repeat whatever the code under test does.
 */
class AnalyticsFixtureTest {

    private static final DateTimeFormatter CHECKING_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final DateTimeFormatter CARD_DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
    private static final Path SEED_CATEGORIES =
            Path.of("src/main/resources/db/migration/V2__seed_categories.sql");
    private static final Pattern MONEY = Pattern.compile("-?\\d+\\.\\d{2}");
    private static final Set<String> MONTHS = Set.of("2025-12", "2026-01", "2026-02", "2026-03", "2026-04");

    /** A statement row as printed: line number, date, signed amount, description, printed balance (checking only). */
    private record Printed(String file, int line, LocalDate date, BigDecimal amount, String description,
                           BigDecimal balance) {
    }

    @Test
    void theKeyCoversEveryStatementRowExactlyOnce() {
        List<String> printed = new ArrayList<>();
        for (Statement statement : AnalyticsFixture.manifest()) {
            for (Printed row : printed(statement.file())) {
                printed.add(row.file() + ":" + row.line() + " " + row.date() + " " + row.amount() + " " + row.description());
            }
        }
        List<String> keyed = AnalyticsFixture.entries().stream()
                .map(e -> e.ref() + " " + e.date() + " " + e.amount() + " " + e.description()).toList();

        assertThat(keyed).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(printed);
    }

    @Test
    void theKeysCategoryStatesAreOnesTheSchemaAllows() throws IOException {
        Set<String> seeded = seededCategories();
        for (Entry e : AnalyticsFixture.entries()) {
            if (e.category() == null) {
                assertThat(e.source()).as(e.ref()).isNull();
            } else {
                assertThat(seeded).as(e.ref()).contains(e.category());
                assertThat(e.source()).as(e.ref()).isIn("RULE", "AI", "USER");
            }
            if (e.aiReviewReason() != null) {
                assertThat(e.category()).as(e.ref() + ": a review means uncategorized").isNull();
                assertThat(e.aiReviewReason()).isIn("LOW_CONFIDENCE", "INVALID_ANSWER");
            }
            if (e.transferExcluded()) {
                assertThat(e.expectedBucket()).as(e.ref() + ": an excluded txn is never linked")
                        .isNotEqualTo(TxnBucket.LINKED_TRANSFER);
            }
        }
        assertThat(AnalyticsFixture.entries()).extracting(Entry::expectedBucket)
                .as("every bucket is exercised").containsAll(List.of(TxnBucket.values()));
    }

    @Test
    void theManifestFitsTheRows() {
        for (Statement statement : AnalyticsFixture.manifest()) {
            List<Printed> rows = printed(statement.file());
            BigDecimal sum = rows.stream().map(Printed::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(rows).as(statement.file()).isNotEmpty().allSatisfy(r -> assertThat(r.date())
                    .isBetween(statement.periodStart(), statement.periodEnd()));
            assertThat(statement.openingBalance().add(sum)).as(statement.file() + ": opening + rows")
                    .isEqualByComparingTo(statement.closingBalance());
            assertThat(statement.sendBalances()).as(statement.file() + ": balances are sent only without a column")
                    .isEqualTo(rows.getFirst().balance() == null);
            if (rows.getFirst().balance() != null) {
                assertThat(balanceColumnMismatches(statement, rows)).as(statement.file())
                        .isEqualTo(statement.expectedBalanceCheck().equals("FAILED")
                                ? List.of(rows.size() + ": printed 10.00 above the rows") : List.of());
            }
        }
    }

    @Test
    void theExpectedTotalsAreWellFormed() {
        Set<String> scopes = new TreeSet<>();
        Set<String> seen = new HashSet<>();
        for (String[] c : AnalyticsFixture.rows("expected-month-totals.csv")) {
            assertThat(c).hasSize(27);
            assertThat(c[0]).isIn("ALL", AnalyticsFixture.CHECKING, AnalyticsFixture.CARD);
            assertThat(c[1]).isIn(MONTHS);
            assertThat(c[2]).isIn("COMPLETE", "PARTIAL", "NONE");
            assertThat(seen.add(c[0] + " " + c[1])).as("one row per scope and month").isTrue();
            for (int i : new int[] {3, 4, 5, 7, 8, 10, 11, 14, 15, 17, 18, 21}) {
                assertThat(c[i]).as(c[0] + " " + c[1] + " column " + i).matches(MONEY);
            }
            scopes.add(c[0]);
        }
        assertThat(seen).hasSize(scopes.size() * MONTHS.size());
    }

    @Test
    void theExpectedAlertsAndSeriesReferToRealRows() {
        Set<String> refs = new HashSet<>();
        AnalyticsFixture.entries().forEach(e -> refs.add(e.ref()));
        List<String[]> alerts = AnalyticsFixture.rows("expected-alerts.csv");
        assertThat(alerts).extracting(c -> c[0]).containsExactlyInAnyOrder("CATEGORY_SPIKE", "CATEGORY_SPIKE",
                "LARGE_TRANSACTION", "POSSIBLE_DUPLICATE", "RECURRING_PRICE_INCREASE", "NEW_RECURRING");
        for (String[] c : alerts) {
            assertThat(c[1]).isIn(MONTHS);
            assertThat(c[3]).isIn("MONEY", "COUNT");
            for (String ref : c[9].isEmpty() ? new String[0] : c[9].split(";")) {
                assertThat(refs).as(c[0] + " " + c[2]).contains(ref);
            }
        }
        List<String[]> series = AnalyticsFixture.rows("expected-recurring.csv");
        assertThat(series).hasSize(3).allSatisfy(c -> {
            assertThat(c[0]).isIn(AnalyticsFixture.CHECKING, AnalyticsFixture.CARD);
            assertThat(c[4]).matches(MONEY);
            assertThat(c[5]).matches(MONEY);
            assertThat(LocalDate.parse(c[10])).isAfter(LocalDate.parse(c[9]));
        });
    }

    private static List<String> balanceColumnMismatches(Statement statement, List<Printed> rows) {
        List<String> mismatches = new ArrayList<>();
        BigDecimal balance = statement.openingBalance();
        for (int i = 0; i < rows.size(); i++) {
            balance = balance.add(rows.get(i).amount());
            BigDecimal off = rows.get(i).balance().subtract(balance);
            if (off.signum() != 0) {
                mismatches.add((i + 1) + ": printed " + off.toPlainString() + " above the rows");
            }
        }
        return mismatches;
    }

    private static List<Printed> printed(String file) {
        List<Printed> rows = new ArrayList<>();
        List<String[]> cells = AnalyticsFixture.rows(file);
        for (int i = 0; i < cells.size(); i++) {
            String[] c = cells.get(i);
            int line = i + 2;
            if (file.startsWith("fixture-checking")) {
                BigDecimal debit = c[2].isEmpty() ? BigDecimal.ZERO : new BigDecimal(c[2]);
                BigDecimal credit = c[3].isEmpty() ? BigDecimal.ZERO : new BigDecimal(c[3]);
                rows.add(new Printed(file, line, LocalDate.parse(c[0], CHECKING_DATE), credit.subtract(debit), c[1],
                        new BigDecimal(c[4])));
            } else {
                rows.add(new Printed(file, line, LocalDate.parse(c[0], CARD_DATE), new BigDecimal(c[2]), c[1], null));
            }
        }
        return rows;
    }

    private static Set<String> seededCategories() throws IOException {
        Set<String> names = new TreeSet<>();
        Matcher m = Pattern.compile("\\('([^']+)',\\s*'(EXPENSE|INCOME|TRANSFER)'\\)")
                .matcher(Files.readString(SEED_CATEGORIES));
        while (m.find()) {
            names.add(m.group(1));
        }
        assertThat(names).as("the seed file must yield categories").hasSize(19);
        return names;
    }
}

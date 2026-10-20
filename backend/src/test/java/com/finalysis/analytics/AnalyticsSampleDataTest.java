package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnswerKeyOracle.Key;
import com.finalysis.analytics.AnswerKeyOracle.Sums;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * ANA-06 (docs/plans/phase-5.md §9.3): the sample data, imported for real with the answer key's categories and
 * transfer detection, against {@link AnswerKeyOracle}, cell by cell. Every category cell (month × bucket × category,
 * for the household and each account) and every month total must match: a 100% match rate. Months March to October
 * are compared, so the months the key has no rows in must be empty too.
 *
 * <p>The broken June file (it lacks one Dining row) must change exactly June's Dining and the June totals built on it,
 * by that row's amount, and flag June.
 *
 * <p>Each test writes its counts to {@code target/phase-5-totals/}, the figures {@code docs/eval/phase-5-totals.md}
 * reports; nothing is written to the repo.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AnalyticsSampleDataTest {

    static final MonthRange RANGE = new MonthRange(YearMonth.of(2026, 3), YearMonth.of(2026, 10));
    static final Path REPORT = Path.of("target", "phase-5-totals");
    private static final String BROKEN = "summit-card_2026-06.csv";
    private static final YearMonth JUNE = YearMonth.of(2026, 6);

    private final ImportTestSupport support;
    private final SampleDataImport sample;
    private final JdbcTemplate jdbc;
    private final MonthlyTotalsService months;
    private final CategoryTotalsService categories;

    AnalyticsSampleDataTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper,
                            MonthlyTotalsService months, CategoryTotalsService categories) {
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.sample = new SampleDataImport(support, jsonMapper);
        this.jdbc = jdbc;
        this.months = months;
        this.categories = categories;
    }

    @BeforeEach
    void databaseHasNoStatements() {
        assertThat(support.count("SELECT count(*) FROM statement")).isZero();
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    @Test
    void everyCellMatchesTheAnswerKey() throws Exception {
        Map<String, Long> accounts = sample.load();
        sample.applyAnswerKey(jdbc, accounts);
        AnswerKeyOracle oracle = AnswerKeyOracle.answerKey();
        assertThat(support.count("SELECT count(*) FROM txn WHERE is_transfer"))
                .as("detection links exactly the key's transfers")
                .isEqualTo((int) oracle.rows().stream().filter(AnswerKeyOracle.KeyRow::transfer).count());

        Comparison comparison = compare(oracle, accounts);

        assertThat(comparison.mismatches()).isEmpty();
        assertThat(comparison.compared()).isEqualTo(comparison.matched());
        assertThat(oracle.months()).as("the key's months").hasSize(6);
        assertThat(oracle.accounts()).hasSize(4);
        report("answer-key", comparison, oracle);
    }

    /**
     * {@code broken/summit-card_2026-06.csv} lacks the 2026-06-01 {@code DOORDASH*THAI BASIL} −23.44 row, so its
     * balance check fails. Imported instead of the good file, every cell still matches the full answer key except
     * June's Dining and the June totals that include it, for the household and Summit, each off by exactly that row.
     * June carries the failed statement's flag.
     */
    @Test
    void theBrokenJuneFileChangesOnlyJunesDiningAndIsFlagged() throws Exception {
        Map<String, Long> accounts = sample.load(Set.of(BROKEN));
        sample.applyAnswerKey(jdbc, accounts, c -> c[0].equals("statements/" + BROKEN) && c[3].contains("THAI BASIL")
                && c[2].equals("2026-06-01"));
        AnswerKeyOracle full = AnswerKeyOracle.answerKey();
        AnswerKeyOracle missingRow = AnswerKeyOracle.answerKey(row -> !(row.file().equals("statements/" + BROKEN)
                && row.description().contains("THAI BASIL") && row.date().toString().equals("2026-06-01")));
        assertThat(full.rows().size() - missingRow.rows().size()).as("one key row is missing from the file")
                .isEqualTo(1);

        Comparison againstFull = compare(full, accounts);
        assertThat(againstFull.mismatches()).containsExactlyInAnyOrder(
                "ALL 2026-06 EXPENSE Dining: -23.44 in 1 more than the actual",
                "summit-card 2026-06 EXPENSE Dining: -23.44 in 1 more than the actual",
                "ALL 2026-06 spending", "ALL 2026-06 net", "ALL 2026-06 savingsRatePercent", "ALL 2026-06 netChange",
                "ALL 2026-06 txnCount",
                "summit-card 2026-06 spending", "summit-card 2026-06 net", "summit-card 2026-06 netChange",
                "summit-card 2026-06 txnCount");
        Comparison againstFile = compare(missingRow, accounts);
        assertThat(againstFile.mismatches()).as("against the rows the file does have, everything matches").isEmpty();

        long statement = jdbc.queryForObject("SELECT id FROM statement WHERE account_id = ? AND period_start = ?",
                Long.class, accounts.get("summit-card"), JUNE.atDay(1));
        assertThat(jdbc.queryForObject("SELECT balance_check FROM statement WHERE id = ?", String.class, statement))
                .isEqualTo("FAILED");
        MonthFlag flag = new MonthFlag(MonthFlagType.RECONCILIATION_FAILED, accounts.get("summit-card"), statement,
                null);
        Map<YearMonth, List<MonthFlag>> flags = new TreeMap<>();
        months.months(Optional.of(RANGE.from()), Optional.of(RANGE.to()), Optional.empty()).months()
                .forEach(m -> flags.put(m.month(), m.dataQuality().flags().stream()
                        .filter(f -> f.type() == MonthFlagType.RECONCILIATION_FAILED).toList()));
        flags.forEach((month, monthFlags) -> assertThat(monthFlags).as(month.toString())
                .isEqualTo(month.equals(JUNE) ? List.of(flag) : List.of()));
        report("broken-june", againstFull, full);
    }

    // ------------------------------------------------------------------ comparison

    /** Every cell compared, how many matched, and a line per mismatch. */
    record Comparison(int compared, int matched, int categoryCells, int monthCells, List<String> mismatches) {
    }

    /**
     * Compares the categories breakdown and the month totals with the oracle, for the household and each account,
     * over {@link #RANGE}. A category cell present on either side is compared; a missing one is empty.
     */
    Comparison compare(AnswerKeyOracle oracle, Map<String, Long> accounts) {
        Map<Key, Sums> expected = oracle.cells();
        List<String> mismatches = new ArrayList<>();
        int categoryCells = 0;
        int monthCells = 0;
        for (String scope : oracle.scopes()) {
            Optional<Long> account = scope.equals(AnswerKeyOracle.ALL) ? Optional.empty()
                    : Optional.of(accounts.get(scope));

            Map<Key, Sums> actual = actualCells(scope, account);
            Set<Key> keys = new TreeSet<>(actual.keySet());
            expected.keySet().stream().filter(k -> k.scope().equals(scope)).forEach(keys::add);
            for (Key key : keys) {
                categoryCells++;
                Sums want = expected.getOrDefault(key, Sums.ZERO);
                Sums got = actual.getOrDefault(key, Sums.ZERO);
                if (!same(want, got)) {
                    mismatches.add(key.scope() + " " + key.month() + " " + key.bucket() + " " + key.category() + ": "
                            + want.net().subtract(got.net()) + " in " + (want.count() - got.count())
                            + " more than the actual");
                }
            }

            for (MonthTotals m : months.months(Optional.of(RANGE.from()), Optional.of(RANGE.to()), account).months()) {
                AnswerKeyOracle.Month want = oracle.month(scope, m.month());
                String where = scope + " " + m.month() + " ";
                monthCells += 8;
                check(mismatches, where + "income", m.income(), want.income());
                check(mismatches, where + "spending", m.spending(), want.spending());
                check(mismatches, where + "net", m.net(), want.net());
                check(mismatches, where + "savingsRatePercent", m.savingsRatePercent(), want.savingsRatePercent());
                check(mismatches, where + "unlinkedTransfers", m.unlinkedTransfers().net(), want.unlinked());
                check(mismatches, where + "linkedTransfers", m.linkedTransfers().net(), want.linked());
                check(mismatches, where + "netChange", m.netChange(), want.netChange());
                if (m.txnCount() != want.txnCount()) {
                    mismatches.add(where + "txnCount");
                }
            }
        }
        int compared = categoryCells + monthCells;
        return new Comparison(compared, compared - mismatches.size(), categoryCells, monthCells, mismatches);
    }

    /** The categories endpoint's non-empty cells, keyed as the oracle keys them. */
    private Map<Key, Sums> actualCells(String scope, Optional<Long> account) {
        Map<Key, Sums> cells = new TreeMap<>();
        CategoryTotalsResponse breakdown = categories.categories(Optional.of(RANGE.from()), Optional.of(RANGE.to()),
                account, Optional.empty(), false);
        for (CategoryTotalsResponse.Row row : breakdown.rows()) {
            for (CategoryTotalsResponse.Cell cell : row.months()) {
                if (cell.flow().count() > 0) {
                    cells.put(new Key(scope, cell.month(), row.bucket().name(),
                                    row.category() == null ? null : row.category().name()),
                            new Sums(cell.flow().moneyOut(), cell.flow().moneyIn(), cell.flow().count()));
                }
            }
        }
        return cells;
    }

    private static boolean same(Sums a, Sums b) {
        return a.moneyOut().compareTo(b.moneyOut()) == 0 && a.moneyIn().compareTo(b.moneyIn()) == 0
                && a.count() == b.count();
    }

    private static void check(List<String> mismatches, String what, BigDecimal actual, BigDecimal expected) {
        boolean same = actual == null ? expected == null : expected != null && actual.compareTo(expected) == 0;
        if (!same) {
            mismatches.add(what);
        }
    }

    private static void report(String name, Comparison comparison, AnswerKeyOracle oracle) throws IOException {
        Files.createDirectories(REPORT);
        Files.writeString(REPORT.resolve(name + ".txt"), String.join("\n",
                "key rows: " + oracle.rows().size(),
                "key months: " + oracle.months(),
                "scopes: " + oracle.scopes(),
                "months compared: " + RANGE.from() + " to " + RANGE.to(),
                "category cells: " + comparison.categoryCells(),
                "month cells: " + comparison.monthCells(),
                "compared: " + comparison.compared(),
                "matched: " + comparison.matched(),
                "mismatches: " + comparison.mismatches()) + "\n");
    }
}

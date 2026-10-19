package com.finalysis.categorize.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.categorize.eval.EvalMetrics.RowSource;
import com.finalysis.categorize.eval.EvalMetrics.ScoredRow;
import com.finalysis.categorize.eval.EvalMetrics.VariantMetrics;
import com.finalysis.categorize.eval.EvalSeeder.Seeded;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The in-suite accuracy regression (CAT-05; docs/plans/phase-4.md §10, step 9d notes): the dev set imported the way
 * the live harness imports it ({@link EvalSeeder}: one account, monthly CSV statements, cleaning and the seed rules
 * through the real import), scored with no AI at all. It pins variant A of the committed dev run: 129 of 148 rows
 * right, none wrong, the other 19 uncategorized.
 *
 * <p>Dev set only. The frozen holdout is never read here: it was evaluated once, by the live harness, and is never
 * evaluated again. A change to the seed rules or the cleaning that moves these numbers fails this test; a wrong
 * answer is a regression, and a new right one means the pinned number is updated on purpose, in review.
 */
@IntegrationTest
@Import(EvalSeeder.class)
class CategorizationAccuracyTest {

    private static final Path DEV = ImportTestSupport.SAMPLE_DATA.resolve("categorization/dev.csv");
    private static final int ROWS = 148;
    private static final int CORRECT = 129;

    private final EvalSeeder seeder;
    private final JdbcTemplate jdbc;

    CategorizationAccuracyTest(EvalSeeder seeder, JdbcTemplate jdbc) {
        this.seeder = seeder;
        this.jdbc = jdbc;
    }

    @AfterEach
    void cleanUp() {
        for (Long account : jdbc.queryForList("SELECT id FROM account WHERE name = ?", Long.class,
                EvalSeeder.ACCOUNT_NAME)) {
            jdbc.update("DELETE FROM txn WHERE account_id = ?", account);
            jdbc.update("DELETE FROM statement WHERE account_id = ?", account);
            jdbc.update("DELETE FROM csv_mapping WHERE account_id = ?", account);
            jdbc.update("DELETE FROM account WHERE id = ?", account);
        }
    }

    @Test
    void rulesOnTheDevSetStayAt129Of148WithNoneWrong() throws IOException {
        EvalSet dev = EvalSet.load("dev", DEV);
        Seeded seeded = seeder.seed(dev.statementLines());

        List<ScoredRow> rows = score(dev, seeded);
        VariantMetrics metrics = EvalMetrics.of(rows);

        assertThat(rows.stream().filter(row -> row.categorized() && !row.correct())
                .map(row -> "row " + row.number() + ": expected " + row.expected() + ", got " + row.predicted()))
                .as("wrong rule answers").isEmpty();
        assertThat(metrics.rows()).isEqualTo(ROWS);
        assertThat(metrics.correct()).as("correct rule answers").isEqualTo(CORRECT);
        assertThat(metrics.wrong()).isZero();
        assertThat(metrics.categorized()).isEqualTo(CORRECT);
        assertThat(metrics.uncategorized()).isEqualTo(ROWS - CORRECT);
        assertThat(metrics.review()).as("no AI ran, so nothing waits for review").isZero();
        assertThat(metrics.bySource().keySet()).containsOnly(RowSource.RULE);
    }

    /** Each row as the rules left it; a linked txn with no category counts as Transfer (§1.1), as in the harness. */
    private List<ScoredRow> score(EvalSet set, Seeded seeded) {
        Map<Long, Integer> rowByTxn = new HashMap<>();
        seeded.txnByRow().forEach((row, txn) -> rowByTxn.put(txn, row));
        List<ScoredRow> rows = new ArrayList<>();
        jdbc.query("""
                SELECT t.id, c.name, t.category_source, t.ai_review_reason, t.is_transfer
                FROM txn t LEFT JOIN category c ON c.id = t.category_id
                WHERE t.account_id = ?
                ORDER BY t.id""", rs -> {
            int number = rowByTxn.get(rs.getLong("id"));
            String predicted = rs.getString("name");
            if (predicted == null && rs.getBoolean("is_transfer")) {
                predicted = "Transfer";
            }
            String source = rs.getString("category_source");
            rows.add(new ScoredRow(number, set.rows().get(number - 1).description(), set.expected(number), predicted,
                    source == null ? RowSource.NONE : RowSource.valueOf(source), rs.getString("ai_review_reason"),
                    null, null, null));
        }, seeded.accountId());
        return rows;
    }
}

package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture.Entry;
import com.finalysis.analytics.AnalyticsFixture.Loaded;
import com.finalysis.analytics.AnalyticsFixture.Statement;
import com.finalysis.analytics.BucketOracle.Kind;
import com.finalysis.analytics.BucketOracle.Row;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The counting rules on the Phase 5 fixture (docs/plans/phase-5.md §1, §9.1): every txn, imported for real, lands in
 * the bucket {@code categories.csv} names, which is also the bucket {@link BucketOracle} gives its stored columns.
 * Each worked example of §1 is one or more of these rows (the key's {@code note} column says which).
 *
 * <p>Imports commit, and transfer detection covers every account, so the test starts from a database without txns.
 */
@IntegrationTest
@AutoConfigureMockMvc
class TxnBucketTest {

    private static final String BUCKETS_SQL = "SELECT t.id, " + TxnBucketSql.BUCKET + """
             AS bucket, t.is_transfer, c.kind, coalesce(c.spending_offset, false) AS spending_offset,
                   t.transfer_excluded, t.amount
            FROM\s""" + TxnBucketSql.FROM + """

            WHERE t.account_id IN (?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;

    TxnBucketTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, jsonMapper);
    }

    @BeforeEach
    void databaseHasNoTxns() {
        assertThat(support.count("SELECT count(*) FROM txn"))
                .as("detection covers every account, so another test's leftover rows would change the links")
                .isZero();
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    @Test
    void everyFixtureTxnLandsInItsExpectedBucket() throws Exception {
        Loaded loaded = fixture.load();
        Map<Long, String> refs = loaded.txns().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        assertThat(refs).as("one txn per key row, none missing").hasSize(AnalyticsFixture.entries().size());

        Map<String, TxnBucket> actual = new TreeMap<>();
        Map<String, TxnBucket> oracle = new TreeMap<>();
        jdbc.query(BUCKETS_SQL, rs -> {
            String ref = refs.get(rs.getLong("id"));
            actual.put(ref, TxnBucket.valueOf(rs.getString("bucket")));
            String kind = rs.getString("kind");
            oracle.put(ref, BucketOracle.bucket(new Row(rs.getBoolean("is_transfer"),
                    kind == null ? null : Kind.valueOf(kind), rs.getBoolean("spending_offset"),
                    rs.getBoolean("transfer_excluded"), rs.getBigDecimal("amount"))));
        }, loaded.accounts().get(AnalyticsFixture.CHECKING), loaded.accounts().get(AnalyticsFixture.CARD));

        Map<String, TxnBucket> expected = new TreeMap<>();
        AnalyticsFixture.entries().forEach(e -> expected.put(e.ref(), e.expectedBucket()));
        assertThat(actual).isEqualTo(expected);
        assertThat(oracle).isEqualTo(expected);
    }

    /** The fixture's premises: detection links exactly the four card payments, and reconciliation is as listed. */
    @Test
    void theFixtureImportsAsDesigned() throws Exception {
        Loaded loaded = fixture.load();

        Set<String> linked = jdbc.queryForList("SELECT id FROM txn WHERE is_transfer", Long.class).stream()
                .map(id -> loaded.txns().entrySet().stream().filter(e -> e.getValue().equals(id))
                        .map(Map.Entry::getKey).findFirst().orElse("?" + id))
                .collect(Collectors.toSet());
        Set<String> expectedLinked = AnalyticsFixture.entries().stream()
                .filter(e -> e.expectedBucket() == TxnBucket.LINKED_TRANSFER).map(Entry::ref).collect(Collectors.toSet());
        assertThat(linked).isEqualTo(expectedLinked);
        assertThat(support.count("SELECT count(*) FROM transfer_link WHERE status = 'MATCHED'")).isEqualTo(4);

        Map<String, String> balanceChecks = new HashMap<>();
        Map<String, String> expectedChecks = new HashMap<>();
        for (Statement statement : AnalyticsFixture.manifest()) {
            balanceChecks.put(statement.file(), jdbc.queryForObject(
                    "SELECT balance_check FROM statement WHERE id = ?", String.class,
                    loaded.statements().get(statement.file())));
            expectedChecks.put(statement.file(), statement.expectedBalanceCheck());
        }
        assertThat(balanceChecks).isEqualTo(expectedChecks);
    }
}

package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.ai.outbound.RedactionContext;
import com.finalysis.ai.outbound.RedactionContextQuery;
import com.finalysis.categorize.CategorizationPromptBuilder.Candidate;
import com.finalysis.categorize.CategorizationPromptBuilder.CategorizationPrompt;
import com.finalysis.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Redaction against the database (SEC-08; docs/plans/phase-4.md §5.2, §5.5): the context comes from the accounts
 * and txns, and building a prompt leaves every stored value as it was. Rolls back.
 */
@IntegrationTest
@Transactional
class RedactionStoredDataTest {

    private final JdbcTemplate jdbc;
    private final CategorizeRows rows;
    private final RedactionContextQuery contextQuery;
    private final CategorizationPromptBuilder builder;

    RedactionStoredDataTest(JdbcTemplate jdbc, MerchantCleaner cleaner, RedactionContextQuery contextQuery,
                            CategorizationPromptBuilder builder) {
        this.jdbc = jdbc;
        this.rows = new CategorizeRows(jdbc, cleaner);
        this.contextQuery = contextQuery;
        this.builder = builder;
    }

    @Test
    void theOutboundCopyIsRedactedAndTheStoredMerchantIsNot() {
        rows.account("Sample Family Checking", "CHECKING", "4821");
        long starbucks = rows.txn("STARBUCKS ARLINGTON VA", "-5.75");
        long billPay = rows.txn("SAMPLE FAMILY CHECKING BILL PAY CITY WATER", "-61.37");
        long zelle = rows.txn("ZELLE PAYMENT TO JANE Q SAMPLE", "-50.00");
        jdbc.update("UPDATE txn SET counterparty = 'JANE Q SAMPLE' WHERE id = ?", zelle);
        List<Map<String, Object>> before = stored(starbucks, billPay, zelle);

        RedactionContext context = contextQuery.load();
        assertThat(context.lastFours()).contains("4821");
        assertThat(context.accountPhrases()).contains("Sample Family Checking", "Test Bank");
        assertThat(context.counterparties()).contains("JANE Q SAMPLE");

        List<Candidate> candidates = jdbc.query("SELECT merchant, counterparty, amount FROM txn WHERE id IN (?, ?, ?) "
                        + "ORDER BY id",
                (rs, row) -> new Candidate(rs.getString("merchant"), rs.getString("counterparty"),
                        rs.getBigDecimal("amount"), List.of()), starbucks, billPay, zelle);
        CategorizationPrompt prompt = builder.build(jdbc.queryForList("SELECT name FROM category", String.class),
                candidates, context);

        assertThat(prompt.request().items()).extracting(CategorizationRequest.Item::merchant)
                .containsExactly("STARBUCKS", "BILL PAY CITY WATER");
        assertThat(prompt.request().items()).extracting(CategorizationRequest.Item::amount)
                .containsExactly("-5.75", "-61.37");
        assertThat(stored(starbucks, billPay, zelle)).as("nothing stored changes").isEqualTo(before);
        assertThat(before.getFirst()).containsEntry("merchant", "STARBUCKS ARLINGTON VA")
                .containsEntry("amount", new BigDecimal("-5.75"));
    }

    private List<Map<String, Object>> stored(long... ids) {
        return jdbc.queryForList("""
                SELECT id, raw_description, merchant, counterparty, amount, fingerprint, category_id, category_source
                FROM txn WHERE id IN (?, ?, ?) ORDER BY id""", ids[0], ids[1], ids[2]);
    }
}

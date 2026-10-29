package com.finalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.ai.AiPath;
import com.finalysis.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V26 (docs/plans/phase-9.md §4.2): every ledger row and turn records its path, and what its stored cost means there:
 * the token estimate on Cloud, 0 on Local, NULL on Self-hosted. The daily cap's and the month's spend sum Cloud rows
 * only, in SQL. Rows written without a path (every row before V26) are Cloud's. The constraints hold the rule too.
 */
@IntegrationTest
class ChatCostByPathTest {

    private final ChatConversationStore store;
    private final JdbcTemplate jdbc;

    ChatCostByPathTest(ChatConversationStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM chat_usage");
        jdbc.update("DELETE FROM chat_conversation");
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    private void record(AiPath path, String estimate) {
        store.recordUsage(new ChatConversationStore.Usage(UsageKind.TURN, "m", path, 1, 1000, 100, null, null, 0,
                new BigDecimal(estimate), now()));
    }

    @Test
    void eachPathStoresItsOwnCostAndOnlyCloudCounts() {
        record(AiPath.CLOUD, "0.100000");
        record(AiPath.LOCAL, "0.050000");
        record(AiPath.SELF_HOSTED, "0.070000");

        assertThat(jdbc.queryForList("SELECT path || ' ' || coalesce(estimated_cost::text, 'NULL') FROM chat_usage "
                + "ORDER BY id", String.class)).containsExactly("CLOUD 0.100000", "LOCAL 0.000000", "SELF_HOSTED NULL");
        assertThat(store.costSince(now().minusHours(1))).isEqualByComparingTo("0.10");
    }

    /** Every row before V26 ran on Cloud: a row written without a path is Cloud's and counts toward the cap. */
    @Test
    void aRowWithoutAPathIsCloudsAndCounts() {
        jdbc.update("INSERT INTO chat_usage (kind, model, calls, estimated_cost, created_at) VALUES ('TURN', 'm', 1, "
                + "0.250000, now())");

        assertThat(jdbc.queryForObject("SELECT path FROM chat_usage", String.class)).isEqualTo("CLOUD");
        assertThat(store.costSince(now().minusHours(1))).isEqualByComparingTo("0.25");
    }

    @Test
    void theConstraintsHoldTheRule() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO chat_usage (kind, model, calls, estimated_cost, created_at, "
                + "path) VALUES ('TURN', 'm', 1, 0.5, now(), 'LOCAL')"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_chat_usage_path_cost");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO chat_usage (kind, model, calls, estimated_cost, created_at, "
                + "path) VALUES ('TURN', 'm', 1, 0.1, now(), 'SELF_HOSTED')"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_chat_usage_path_cost");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO chat_usage (kind, model, calls, created_at, path) "
                + "VALUES ('TURN', 'm', 1, now(), 'MARS')"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_chat_usage_path");
        long conversation = store.create(now()).id();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO chat_turn (conversation_id, seq, question, outbound_question, answer, answer_source,
                    grounding_status, alias_map, model, prompt_version, rounds, tool_calls, millis, estimated_cost, path)
                VALUES (?, 1, 'q', 'q', 'an answer', 'MODEL', 'GROUNDED', '{}', 'm', 'chat-10', 1, 0, 1, 0.2, 'LOCAL')""",
                conversation))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_chat_turn_path_cost");
    }

    @Test
    void theStoredCostRule() {
        assertThat(ChatConversationStore.storedCost(AiPath.CLOUD, new BigDecimal("0.01"))).isEqualByComparingTo("0.01");
        assertThat(ChatConversationStore.storedCost(AiPath.CLOUD, null)).as("an unpriced Cloud model").isNull();
        assertThat(ChatConversationStore.storedCost(AiPath.LOCAL, new BigDecimal("0.01"))).isZero();
        assertThat(ChatConversationStore.storedCost(AiPath.LOCAL, null)).isZero();
        assertThat(ChatConversationStore.storedCost(AiPath.SELF_HOSTED, new BigDecimal("0.01"))).isNull();
    }

    /** The API says what a turn's cost fields mean on each path: Self-hosted's are null, and the note says why. */
    @Test
    void theTurnsUsageSaysWhatItsCostMeans() {
        assertThat(ChatTurnResponse.Usage.of("m", AiPath.CLOUD, 1, 10, 5, 0, 0, 0, new BigDecimal("0.0123456")))
                .extracting(ChatTurnResponse.Usage::estimatedCost, ChatTurnResponse.Usage::costNote)
                .containsExactly("0.012346", null);
        assertThat(ChatTurnResponse.Usage.of("m", AiPath.LOCAL, 1, 10, 5, 0, 0, 0, null))
                .extracting(ChatTurnResponse.Usage::estimatedCost, ChatTurnResponse.Usage::costNote)
                .containsExactly("0.000000", null);
        ChatTurnResponse.Usage selfHosted = ChatTurnResponse.Usage.of("m", AiPath.SELF_HOSTED, 1, 10, 5, 0, 0, 0, null);
        assertThat(selfHosted.estimatedCost()).isNull();
        assertThat(selfHosted.costNote()).isEqualTo(ChatTurnResponse.Usage.SELF_HOSTED_COST)
                .contains("running time").contains("9c");
        assertThat(selfHosted.path()).isEqualTo(AiPath.SELF_HOSTED);
    }
}

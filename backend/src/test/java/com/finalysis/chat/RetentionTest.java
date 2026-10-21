package com.finalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.config.SchedulingConfiguration;
import com.finalysis.support.ChatIntegrationTest;
import com.finalysis.support.MutableClock;
import java.sql.Timestamp;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Conversation retention (docs/plans/phase-6.md §7.4, §18 decision 8): a conversation idle for 90 days goes with its
 * turns, tool calls and summary; activity is the last turn, not the creation; ledger rows older than that go too. The
 * purge runs at startup and nightly.
 */
@ChatIntegrationTest
class RetentionTest {

    private final ChatRetention retention;
    private final JdbcTemplate jdbc;
    private final MutableClock clock;

    RetentionTest(ChatRetention retention, JdbcTemplate jdbc, MutableClock clock) {
        this.retention = retention;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @BeforeEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_usage");
    }

    @AfterEach
    void cleanUp() {
        clean();
    }

    private Timestamp daysAgo(int days) {
        return Timestamp.from(clock.instant().minus(Duration.ofDays(days)));
    }

    /** A conversation created {@code created} days ago, last active {@code active} days ago, with a turn and a call. */
    private long conversation(int created, int active) {
        long id = jdbc.queryForObject("INSERT INTO chat_conversation (title, summary, summary_through, created_at, "
                + "updated_at) VALUES ('q', 'Earlier: dining in [amount].', 1, ?, ?) RETURNING id", Long.class,
                daysAgo(created), daysAgo(active));
        long turn = jdbc.queryForObject("""
                INSERT INTO chat_turn (conversation_id, seq, question, outbound_question, answer, answer_source,
                    grounding_status, alias_map, model, prompt_version, rounds, tool_calls, millis, created_at)
                VALUES (?, 1, 'q', 'q', 'a', 'MODEL', 'NO_FIGURES', '{}', 'm', 'chat-3', 1, 1, 1, ?) RETURNING id""",
                Long.class, id, daysAgo(active));
        jdbc.update("INSERT INTO chat_tool_call (turn_id, seq, tool, arguments, status, millis) "
                + "VALUES (?, 1, 'get_month_totals', '{}', 'OK', 1)", turn);
        return id;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void aConversationIdleNinetyDaysGoesWithItsTurnsToolCallsAndSummary() {
        long idle = conversation(120, 91);
        long recent = conversation(120, 89);
        jdbc.update("INSERT INTO chat_usage (kind, model, calls, estimated_cost, created_at) VALUES "
                + "('TURN', 'm', 1, 0.010000, ?), ('TURN', 'm', 1, 0.020000, ?)", daysAgo(91), daysAgo(1));

        ChatConversationStore.Purged purged = retention.purge();

        assertThat(purged).isEqualTo(new ChatConversationStore.Purged(1, 1));
        assertThat(count("SELECT count(*) FROM chat_conversation WHERE id = ?", idle)).isZero();
        assertThat(count("SELECT count(*) FROM chat_turn WHERE conversation_id = ?", idle)).isZero();
        assertThat(count("SELECT count(*) FROM chat_tool_call c JOIN chat_turn t ON t.id = c.turn_id")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM chat_conversation WHERE id = ? AND summary IS NOT NULL", recent))
                .as("activity, not creation, keeps it").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM chat_turn WHERE conversation_id = ?", recent)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM chat_usage")).isEqualTo(1);
    }

    @Test
    void theClockDecidesWhatIsIdle() {
        long id = conversation(10, 10);
        assertThat(retention.purge().conversations()).isZero();

        clock.advance(Duration.ofDays(81));
        assertThat(retention.purge().conversations()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM chat_conversation WHERE id = ?", id)).isZero();
    }

    @Test
    void itRunsAtStartupAndNightly() throws Exception {
        assertThat(ChatRetention.class.getMethod("atStartup").getAnnotation(EventListener.class).value())
                .containsExactly(ApplicationReadyEvent.class);
        assertThat(ChatRetention.class.getMethod("nightly").getAnnotation(Scheduled.class).cron())
                .isEqualTo("0 30 3 * * *");
        assertThat(SchedulingConfiguration.class.getAnnotation(EnableScheduling.class)).isNotNull();
    }
}

package com.finalysis.chat;

import com.finalysis.chat.citation.Citation;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one writer of the chat tables (docs/plans/phase-6.md §7.3, §9): conversations, turns and their tool calls.
 * Writes happen after a turn's rounds, outside the tools' read-only transactions; nothing in {@code chat.tool} may
 * depend on this class ({@code ChatReadOnlyTest}). Plain SQL, like the other chat queries. It writes its own JSON
 * columns (citations, the alias map, tool arguments): local data that never goes to the model, so it is the one other
 * class in chat allowed to serialize ({@code ChatToolPathTest}).
 */
@Component
public class ChatConversationStore {

    static final int TITLE_LENGTH = 80;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ChatConversationStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Created(long id, OffsetDateTime createdAt) {
    }

    public record Summary(long id, String title, int turns, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record Page(List<Summary> items, long total) {
    }

    /**
     * One tool call to store.
     *
     * @param tool      a registry name, or null for a name the model invented (stored as {@code (unknown)})
     * @param arguments the model's arguments as sent; kept only for a known tool and only when a JSON object
     */
    public record NewToolCall(String tool, String arguments, ToolStatus status, int rows, boolean truncated,
                              long millis) {
    }

    /** One turn to store; {@code aliasMap} is alias → account id as of the turn. */
    public record NewTurn(long conversationId, String question, String outboundQuestion, String answer,
                          AnswerSource source, String groundingStatus, boolean retried, int figuresChecked,
                          int ungrounded, LimitHit limitHit, List<Citation> citations, Map<String, Long> aliasMap,
                          String model,
                          String promptVersion, int rounds, int toolCalls, Integer inputTokens, Integer outputTokens,
                          int callsWithoutUsage, BigDecimal estimatedCost, long millis, String error,
                          OffsetDateTime createdAt, List<NewToolCall> calls) {

        public NewTurn {
            calls = List.copyOf(calls);
        }
    }

    /** A stored turn as read back; {@code citations} is the stored JSON array. */
    public record StoredTurn(int seq, String question, String outboundQuestion, String answer, AnswerSource source,
                             String groundingStatus, boolean retried, int figuresChecked, int ungrounded,
                             LimitHit limitHit, String citations, String model, int rounds, int toolCalls,
                             Integer inputTokens, Integer outputTokens, int callsWithoutUsage,
                             BigDecimal estimatedCost, long millis, OffsetDateTime createdAt) {
    }

    @Transactional
    public Created create(OffsetDateTime now) {
        return jdbc.sql("INSERT INTO chat_conversation (created_at, updated_at) VALUES (:now, :now) "
                        + "RETURNING id, created_at")
                .param("now", now)
                .query((rs, n) -> new Created(rs.getLong("id"), rs.getObject("created_at", OffsetDateTime.class)))
                .single();
    }

    @Transactional(readOnly = true)
    public boolean exists(long id) {
        return jdbc.sql("SELECT count(*) FROM chat_conversation WHERE id = :id").param("id", id)
                .query(Long.class).single() > 0;
    }

    /** Newest activity first. */
    @Transactional(readOnly = true)
    public Page list(int page, int size) {
        long total = jdbc.sql("SELECT count(*) FROM chat_conversation").query(Long.class).single();
        List<Summary> items = jdbc.sql("""
                        SELECT c.id, c.title, c.created_at, c.updated_at,
                               (SELECT count(*) FROM chat_turn t WHERE t.conversation_id = c.id) AS turns
                        FROM chat_conversation c
                        ORDER BY c.updated_at DESC, c.id DESC
                        LIMIT :size OFFSET :offset""")
                .param("size", size).param("offset", (long) page * size)
                .query((rs, n) -> new Summary(rs.getLong("id"), rs.getString("title"), rs.getInt("turns"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .list();
        return new Page(items, total);
    }

    @Transactional(readOnly = true)
    public Optional<Summary> find(long id) {
        return jdbc.sql("""
                        SELECT c.id, c.title, c.created_at, c.updated_at,
                               (SELECT count(*) FROM chat_turn t WHERE t.conversation_id = c.id) AS turns
                        FROM chat_conversation c WHERE c.id = :id""")
                .param("id", id)
                .query((rs, n) -> new Summary(rs.getLong("id"), rs.getString("title"), rs.getInt("turns"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .optional();
    }

    @Transactional(readOnly = true)
    public List<StoredTurn> turns(long conversationId) {
        return jdbc.sql("""
                        SELECT seq, question, outbound_question, answer, answer_source, grounding_status, retried,
                               figures_checked, ungrounded, limit_hit, citations::text AS citations, model, rounds,
                               tool_calls, input_tokens, output_tokens, calls_without_usage, estimated_cost, millis,
                               created_at
                        FROM chat_turn WHERE conversation_id = :id ORDER BY seq""")
                .param("id", conversationId)
                .query((rs, n) -> new StoredTurn(rs.getInt("seq"), rs.getString("question"),
                        rs.getString("outbound_question"), rs.getString("answer"),
                        AnswerSource.valueOf(rs.getString("answer_source")), rs.getString("grounding_status"),
                        rs.getBoolean("retried"), rs.getInt("figures_checked"), rs.getInt("ungrounded"),
                        rs.getString("limit_hit") == null ? null : LimitHit.valueOf(rs.getString("limit_hit")),
                        rs.getString("citations"), rs.getString("model"), rs.getInt("rounds"),
                        rs.getInt("tool_calls"), rs.getObject("input_tokens", Integer.class),
                        rs.getObject("output_tokens", Integer.class), rs.getInt("calls_without_usage"),
                        rs.getBigDecimal("estimated_cost"), rs.getLong("millis"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    /** Deletes a conversation and, by cascade, its turns and tool calls; false when there was none. */
    @Transactional
    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM chat_conversation WHERE id = :id").param("id", id).update() > 0;
    }

    /**
     * Stores a turn and its tool calls under the conversation's next number, sets the title from the first question
     * and the conversation's activity time. The conversation row is locked, so numbers never collide.
     *
     * @return the turn's number in its conversation, from 1
     */
    @Transactional
    public int saveTurn(NewTurn turn) {
        jdbc.sql("SELECT id FROM chat_conversation WHERE id = :id FOR UPDATE").param("id", turn.conversationId())
                .query(Long.class).single();
        int seq = jdbc.sql("SELECT coalesce(max(seq), 0) + 1 FROM chat_turn WHERE conversation_id = :id")
                .param("id", turn.conversationId()).query(Integer.class).single();
        long turnId = jdbc.sql("""
                        INSERT INTO chat_turn (conversation_id, seq, question, outbound_question, answer, answer_source,
                            grounding_status, retried, figures_checked, ungrounded, limit_hit, citations, alias_map,
                            model, prompt_version, rounds, tool_calls, input_tokens, output_tokens,
                            calls_without_usage, estimated_cost, millis, error, created_at)
                        VALUES (:conversation, :seq, :question, :outbound, :answer, :source, :grounding, :retried,
                            :figures, :ungrounded, :limit, CAST(:citations AS jsonb), CAST(:aliases AS jsonb), :model,
                            :prompt, :rounds, :toolCalls, :input, :output, :withoutUsage, :cost, :millis, :error,
                            :createdAt)
                        RETURNING id""")
                .param("conversation", turn.conversationId()).param("seq", seq)
                .param("question", turn.question()).param("outbound", turn.outboundQuestion())
                .param("answer", turn.answer()).param("source", turn.source().name())
                .param("grounding", turn.groundingStatus()).param("retried", turn.retried())
                .param("figures", turn.figuresChecked()).param("ungrounded", turn.ungrounded())
                .param("limit", turn.limitHit() == null ? null : turn.limitHit().name())
                .param("citations", json.writeValueAsString(turn.citations()))
                .param("aliases", json.writeValueAsString(turn.aliasMap()))
                .param("model", turn.model()).param("prompt", turn.promptVersion())
                .param("rounds", turn.rounds()).param("toolCalls", turn.toolCalls())
                .param("input", turn.inputTokens()).param("output", turn.outputTokens())
                .param("withoutUsage", turn.callsWithoutUsage()).param("cost", turn.estimatedCost())
                .param("millis", (int) Math.min(Integer.MAX_VALUE, turn.millis())).param("error", turn.error())
                .param("createdAt", turn.createdAt())
                .query(Long.class).single();
        int callSeq = 0;
        for (NewToolCall call : turn.calls()) {
            jdbc.sql("""
                            INSERT INTO chat_tool_call (turn_id, seq, tool, arguments, status, rows, truncated, millis)
                            VALUES (:turn, :seq, :tool, CAST(:arguments AS jsonb), :status, :rows, :truncated, :millis)""")
                    .param("turn", turnId).param("seq", ++callSeq)
                    .param("tool", call.tool() == null ? "(unknown)" : call.tool())
                    .param("arguments", arguments(call)).param("status", call.status().name())
                    .param("rows", call.rows()).param("truncated", call.truncated())
                    .param("millis", (int) Math.min(Integer.MAX_VALUE, call.millis()))
                    .update();
        }
        String title = turn.question().strip();
        if (title.length() > TITLE_LENGTH) {
            title = title.substring(0, TITLE_LENGTH);
        }
        jdbc.sql("UPDATE chat_conversation SET title = coalesce(title, :title), updated_at = :now WHERE id = :id")
                .param("title", title.isEmpty() ? null : title).param("now", turn.createdAt())
                .param("id", turn.conversationId()).update();
        return seq;
    }

    /** The arguments as stored: a known tool's, when they are a JSON object; else {@code {}}. Never malformed text. */
    private String arguments(NewToolCall call) {
        if (call.tool() == null || call.arguments() == null || call.arguments().isBlank()) {
            return "{}";
        }
        try {
            JsonNode node = json.readTree(call.arguments());
            return node != null && node.isObject() ? json.writeValueAsString(node) : "{}";
        } catch (JacksonException e) {
            return "{}";
        }
    }

    /** Estimated cost of every turn stored since a time: the daily cap's spend (§8). Unpriced turns count 0. */
    @Transactional(readOnly = true)
    public BigDecimal costSince(OffsetDateTime from) {
        return jdbc.sql("SELECT coalesce(sum(estimated_cost), 0) FROM chat_turn WHERE created_at >= :from")
                .param("from", from).query(BigDecimal.class).single();
    }
}

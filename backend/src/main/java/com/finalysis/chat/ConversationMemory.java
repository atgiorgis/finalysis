package com.finalysis.chat;

import com.finalysis.ai.AiPrices;
import com.finalysis.chat.grounding.AnswerText;
import com.finalysis.chat.grounding.SummaryFigureStripper;
import com.finalysis.chat.outbound.ChatPayloadGuard;
import com.finalysis.chat.outbound.OutboundTurn;
import com.finalysis.chat.outbound.PayloadRefusedException;
import com.finalysis.chat.outbound.ToolResultWriter;
import com.finalysis.chat.tool.view.HistoryView;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * A conversation's memory (CHT-06, docs/plans/phase-6.md §7.1, §7.2): what the next prompt carries of earlier turns, and
 * folding older turns into the summary.
 * <ul>
 *   <li><b>The window</b> ({@link #window}): the summary, then the turns not folded into it, oldest first, as their
 *       outbound questions and their answers as shown, newest first while the whole stays within
 *       {@value #HISTORY_TOKENS} estimated tokens. Failed turns have no answer and are left out.</li>
 *   <li><b>No figure survives.</b> Every earlier answer and every summary goes through {@link SummaryFigureStripper}
 *       before it is sent or stored: grounding is per turn, so every figure in a new answer needs a new tool call.</li>
 *   <li><b>Folding</b> ({@link #foldIfDue}), after a turn is stored: when more than {@value #FOLD_AFTER} turns are
 *       outside the summary, or the newest {@value #RECENT_TURNS} don't fit the window, the older ones are folded so
 *       that at most {@value #RECENT_TURNS} remain: one call with the main role, at most
 *       {@value #SUMMARY_OUTPUT_TOKENS} output tokens, given only guard-checked text (the previous summary and the folded
 *       turns, figures stripped). The call counts against the turn's limits (time, input tokens, cost); its tokens are
 *       logged ({@code role=chat-summary}) and its cost goes to the usage ledger. A failed or skipped summary leaves the
 *       fold point where it was, so the next turn tries again and the window simply stays longer until then.</li>
 * </ul>
 */
@Component
public class ConversationMemory {

    static final int RECENT_TURNS = 4;
    static final int FOLD_AFTER = 6;
    static final int HISTORY_TOKENS = 1_500;
    static final int SUMMARY_OUTPUT_TOKENS = 400;
    /** The longest earlier answer sent back; a longer one is cut at a word. */
    static final int MAX_ANSWER_CHARS = 1_000;
    static final String RESOURCE = "chat/summary-prompt.txt";

    private static final Logger log = LoggerFactory.getLogger(ConversationMemory.class);

    private final ChatConversationStore store;
    private final ChatModelClient client;
    private final ChatPayloadGuard guard;
    private final ToolResultWriter writer;
    private final ChatProperties limits;
    private final AiPrices prices;
    private final Clock clock;
    private final String prompt;

    public ConversationMemory(ChatConversationStore store, ChatModelClient client, ChatPayloadGuard guard,
                              ToolResultWriter writer, ChatProperties limits, AiPrices prices, Clock clock) {
        this.store = store;
        this.client = client;
        this.guard = guard;
        this.writer = writer;
        this.limits = limits;
        this.prices = prices;
        this.clock = clock;
        try (InputStream in = ConversationMemory.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing");
            }
            this.prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * What the turn has spent so far, for the summary call's limits.
     *
     * @param startedMillis  the turn's start on the clock
     * @param inputForLimits input tokens counted against the per-turn cap (reported, or estimated when not)
     * @param cost           estimated cost so far; null when the model has no price
     */
    public record Spent(long startedMillis, long inputForLimits, BigDecimal cost) {
    }

    /** How a fold ended, for the log and the tests. */
    public enum Fold { NOT_DUE, FOLDED, NOTHING_TO_SEND, SKIPPED_LIMIT, FAILED }

    // ------------------------------------------------------------------ the window

    /** The history for a conversation's next prompt; {@link ChatHistory#NONE} for a new one. */
    public ChatHistory window(long conversationId) {
        ChatConversationStore.Memory memory = store.memory(conversationId).orElse(null);
        if (memory == null) {
            return ChatHistory.NONE;
        }
        ChatPayloadGuard.Checked<String> summary = summaryOf(memory);
        int budget = HISTORY_TOKENS - (summary == null ? 0 : TokenEstimate.ofText(writer.summary(summary)));
        List<ChatHistory.Turn> picked = new ArrayList<>();
        List<ChatConversationStore.PastTurn> turns = memory.turns();
        for (int i = turns.size() - 1; i >= 0; i--) {
            ChatHistory.Turn turn = sendable(turns.get(i));
            if (turn == null) {
                continue;
            }
            int tokens = tokens(turn);
            if (tokens > budget) {
                break;
            }
            budget -= tokens;
            picked.add(turn);
        }
        Collections.reverse(picked);
        return new ChatHistory(summary, picked);
    }

    /** The stored summary, stripped again (it was stripped when stored) and checked; null when there is none. */
    private ChatPayloadGuard.Checked<String> summaryOf(ChatConversationStore.Memory memory) {
        String summary = SummaryFigureStripper.strip(memory.summary());
        return guard.allowsHistory(summary) ? guard.checkHistory(summary) : null;
    }

    /** A turn as it may be sent back: its outbound question and its stripped answer, both checked; else null. */
    private ChatHistory.Turn sendable(ChatConversationStore.PastTurn turn) {
        String answer = stripped(turn);
        if (answer == null || !guard.allowsText(turn.outboundQuestion())) {
            return null;
        }
        return new ChatHistory.Turn(guard.checkText(turn.outboundQuestion()), guard.checkHistory(answer));
    }

    /** An answer as history: figures stripped, cut to {@value #MAX_ANSWER_CHARS} characters; null if nothing is left. */
    static String strippedAnswer(String answer) {
        String stripped = cut(SummaryFigureStripper.strip(answer), MAX_ANSWER_CHARS);
        return stripped.isBlank() ? null : stripped;
    }

    private String stripped(ChatConversationStore.PastTurn turn) {
        if (turn.source() == AnswerSource.ERROR || turn.answer() == null) {
            return null;
        }
        String answer = strippedAnswer(turn.answer());
        return answer != null && guard.allowsHistory(answer) ? answer : null;
    }

    private int tokens(ChatHistory.Turn turn) {
        return TokenEstimate.ofText("<question>" + writer.question(turn.question()) + "</question>")
                + TokenEstimate.ofText(writer.answer(turn.answer()));
    }

    // ------------------------------------------------------------------ folding

    /**
     * Folds the older turns into the summary when it is due (§7.2). Never throws: a failure is logged, and the next turn
     * tries again.
     */
    public Fold foldIfDue(long conversationId, OutboundTurn turn, Spent spent) {
        ChatConversationStore.Memory memory = store.memory(conversationId).orElse(null);
        if (memory == null) {
            return Fold.NOT_DUE;
        }
        List<ChatConversationStore.PastTurn> outside = memory.turns();
        int keep = keep(memory, outside);
        if (outside.size() <= FOLD_AFTER && keep == Math.min(RECENT_TURNS, outside.size())) {
            return Fold.NOT_DUE;
        }
        List<ChatConversationStore.PastTurn> folded = outside.subList(0, outside.size() - keep);
        if (folded.isEmpty()) {
            return Fold.NOT_DUE;
        }
        int through = folded.getLast().seq();

        List<HistoryView.Turn> rows = new ArrayList<>();
        for (ChatConversationStore.PastTurn past : folded) {
            String answer = stripped(past);
            if (answer != null) {
                rows.add(new HistoryView.Turn(past.outboundQuestion(), answer));
            }
        }
        if (rows.isEmpty()) {
            // only failed turns: nothing to tell the model; the summary stays as it is
            store.saveSummary(conversationId, memory.summaryThrough(), through, memory.summary());
            return logged(conversationId, folded.size(), Fold.NOTHING_TO_SEND, null);
        }
        ChatPayloadGuard.Checked<String> previous = summaryOf(memory);
        ChatPayloadGuard.Checked<HistoryView> history;
        try {
            history = guard.check(new HistoryView(previous == null ? null : previous.view(), rows), turn);
        } catch (PayloadRefusedException e) {
            return logged(conversationId, folded.size(), Fold.FAILED, null);
        }
        List<Message> messages = List.of(new SystemMessage(prompt),
                new UserMessage("<history>" + writer.history(history) + "</history>"));

        UsageTally usage = new UsageTally(prices.of(client.modelId()));
        int estimate = TokenEstimate.of(messages, 0);
        LimitHit limit = limit(spent, estimate, usage);
        if (limit != null) {
            return logged(conversationId, folded.size(), Fold.SKIPPED_LIMIT, limit);
        }
        ChatModelClient.Reply reply;
        try {
            reply = client.complete(messages, SUMMARY_OUTPUT_TOKENS, ChatModelClient.SUMMARY_ROLE,
                    history.view().turns().size());
        } catch (RuntimeException e) {
            return logged(conversationId, folded.size(), Fold.FAILED, null);
        }
        usage.add(reply, estimate, SUMMARY_OUTPUT_TOKENS);
        store.recordUsage(usage.toUsage(UsageKind.CONVERSATION_SUMMARY, client.modelId(), now()));

        String summary = cut(SummaryFigureStripper.strip(AnswerText.plain(reply.text()).text()),
                ChatPayloadGuard.MAX_HISTORY);
        if (!guard.allowsHistory(summary)
                || !store.saveSummary(conversationId, memory.summaryThrough(), through, summary)) {
            return logged(conversationId, folded.size(), Fold.FAILED, null);
        }
        return logged(conversationId, folded.size(), Fold.FOLDED, null);
    }

    /** How many of the newest turns stay verbatim: at most {@value #RECENT_TURNS}, fewer while they don't fit. */
    private int keep(ChatConversationStore.Memory memory, List<ChatConversationStore.PastTurn> outside) {
        ChatPayloadGuard.Checked<String> summary = summaryOf(memory);
        int budget = HISTORY_TOKENS - (summary == null ? 0 : TokenEstimate.ofText(writer.summary(summary)));
        int keep = Math.min(RECENT_TURNS, outside.size());
        while (keep > 1 && tokens(outside.subList(outside.size() - keep, outside.size())) > budget) {
            keep--;
        }
        return keep;
    }

    private int tokens(List<ChatConversationStore.PastTurn> turns) {
        int tokens = 0;
        for (ChatConversationStore.PastTurn past : turns) {
            ChatHistory.Turn turn = sendable(past);
            tokens += turn == null ? 0 : tokens(turn);
        }
        return tokens;
    }

    /** The turn's limits, with this call's worst case: the first one it would pass, or null. */
    private LimitHit limit(Spent spent, int estimate, UsageTally usage) {
        if (clock.millis() - spent.startedMillis() >= limits.maxTurnTime().toMillis()) {
            return LimitHit.TIME;
        }
        if (estimate > limits.maxInputTokensPerCall()
                || spent.inputForLimits() + estimate > limits.maxInputTokensPerTurn()) {
            return LimitHit.TOKENS;
        }
        if (limits.maxTurnCost().signum() > 0 && spent.cost() != null
                && spent.cost().add(usage.worstCase(estimate, SUMMARY_OUTPUT_TOKENS))
                .compareTo(limits.maxTurnCost()) > 0) {
            return LimitHit.COST;
        }
        return null;
    }

    private static Fold logged(long conversationId, int folded, Fold outcome, LimitHit limit) {
        if (limit == null) {
            log.info("chat summary conversation={} folded={} outcome={}", conversationId, folded, outcome);
        } else {
            log.info("chat summary conversation={} folded={} outcome={} limit={}", conversationId, folded, outcome,
                    limit);
        }
        return outcome;
    }

    /** A text cut to at most {@code max} characters, at the last space before it when there is one. */
    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int space = text.lastIndexOf(' ', max - 1);
        return (space > max / 2 ? text.substring(0, space) : text.substring(0, max - 1)).strip() + "…";
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}

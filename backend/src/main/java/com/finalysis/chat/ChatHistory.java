package com.finalysis.chat;

import com.finalysis.chat.outbound.ChatPayloadGuard;
import java.util.List;

/**
 * What a turn's prompt carries of the conversation (CHT-06, docs/plans/phase-6.md §7.1), every text checked by the
 * guard: the summary of older turns, and the recent turns, oldest first, each its outbound question and its answer as
 * shown with every figure stripped ({@code SummaryFigureStripper}). Built by {@link ConversationMemory}.
 *
 * @param summary null when nothing has been folded yet
 */
public record ChatHistory(ChatPayloadGuard.Checked<String> summary, List<Turn> turns) {

    public static final ChatHistory NONE = new ChatHistory(null, List.of());

    public ChatHistory {
        turns = List.copyOf(turns);
    }

    /** One earlier turn as sent back. */
    public record Turn(ChatPayloadGuard.Checked<String> question, ChatPayloadGuard.Checked<String> answer) {
    }
}

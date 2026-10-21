package com.finalysis.chat;

import com.finalysis.chat.citation.Citation;
import java.time.OffsetDateTime;
import java.util.List;

/** The conversation endpoints' bodies (docs/plans/phase-6.md §10.2). Local only. */
public final class ConversationResponses {

    private ConversationResponses() {
    }

    public record Created(long id, OffsetDateTime createdAt) {
    }

    public record Item(long id, String title, int turns, OffsetDateTime updatedAt) {
    }

    public record Page(List<Item> items, int page, int size, int totalPages) {
    }

    /** One conversation with its turns; citations re-resolved, so a txn whose statement was deleted reads GONE. */
    public record Detail(long id, String title, OffsetDateTime createdAt, OffsetDateTime updatedAt, List<Turn> turns) {
    }

    /**
     * A stored turn. {@code answer} is null for a turn that failed ({@code ERROR}).
     *
     * @param outboundQuestion what was sent instead of the question as typed
     */
    public record Turn(int turn, String question, String outboundQuestion, String answer, AnswerSource answerSource,
                       ChatTurnResponse.Grounding grounding, List<Citation> citations, LimitHit limitHit,
                       ChatTurnResponse.Usage usage, OffsetDateTime createdAt) {
    }
}

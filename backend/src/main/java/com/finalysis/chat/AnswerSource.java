package com.finalysis.chat;

/** Where a turn's answer came from (docs/plans/phase-6.md §5.5, §10.2); stored as {@code chat_turn.answer_source}. */
public enum AnswerSource {
    /** The model's answer, every figure grounded. */
    MODEL,
    /** The Java-built list of the turn's tool results: ungrounded after the retry, or a limit was hit. */
    FALLBACK,
    /** A model call failed; there is no answer. */
    ERROR
}

package com.finalysis.chat;

/** The first turn limit a turn reached (docs/plans/phase-6.md §8); stored as {@code chat_turn.limit_hit}. */
public enum LimitHit {
    /** Model rounds per turn: tool rounds, the answer and the grounding retry. */
    ROUNDS,
    /** Tool calls per turn: later calls were not run. */
    TOOL_CALLS,
    /** Input tokens, per call (after cutting the oldest results) or per turn. */
    TOKENS,
    /** Wall time per turn. */
    TIME,
    /** Estimated cost per turn. */
    COST
}

package com.finalysis.chat;

/** How a tool call ended (docs/plans/phase-6.md §8): the count-only log line and the turn's tool list carry it. */
public enum ToolStatus {
    /** The result was read, projected, checked and written. */
    OK,
    /** An unknown tool, or arguments that fail the schema, a check between parameters, or the service's validation. */
    INVALID_ARGUMENTS,
    /** The read failed; the model is told to answer without it. */
    FAILED,
    /** The guard refused the result as a whole; nothing of it was sent. */
    REFUSED
}

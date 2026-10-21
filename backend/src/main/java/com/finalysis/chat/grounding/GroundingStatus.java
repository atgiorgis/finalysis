package com.finalysis.chat.grounding;

/** Which path a turn's answer took (docs/plans/phase-6.md §5.5): the response's {@code grounding.status}. */
public enum GroundingStatus {
    /** Every figure matched this turn's tool results. */
    GROUNDED,
    /** The answer has no figures. */
    NO_FIGURES,
    /** Figures stayed ungrounded after the retry (or a limit was hit): the Java-built list of results was shown. */
    FALLBACK
}

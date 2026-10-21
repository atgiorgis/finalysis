package com.finalysis.chat.grounding;

import java.math.BigDecimal;

/**
 * One figure found in an answer (docs/plans/phase-6.md §5.2): its kind, the form it was written in, its magnitude and
 * where it is. The sign is dropped (§5.4: prose says "spent $45.00" for −45.00).
 *
 * @param asWritten the figure's own characters ({@code -$1,183.67}, {@code 384%}); never more of the answer, so the
 *                  retry message can quote it safely
 * @param start     its first character in the answer
 * @param end       one past its last character
 */
public record Figure(Kind kind, Form form, BigDecimal magnitude, String asWritten, int start, int end) {

    public enum Kind {
        /** {@code $} before a number, or a number with exactly two decimals. */
        MONEY,
        /** A number followed by {@code %} or the word {@code percent}. */
        PERCENT,
        /** Any other number. */
        COUNT
    }

    /** How a figure was written, which decides how it may match (§5.4). */
    public enum Form {
        /** Money with cents, a percent with one decimal, a whole count: matches a value exactly. */
        EXACT,
        /** Money without cents or a whole percent: matches a value rounded half-even to whole units. */
        WHOLE,
        /** Any other form ({@code $12.5}, {@code 12.50%}, {@code 2.5 months}, {@code $1.2k}): never matches. */
        UNMATCHABLE
    }
}

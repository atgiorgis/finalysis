package com.finalysis.ingestion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Checks money values against the {@code NUMERIC(12,2)} columns they will be written to. */
public final class Money {

    /** NUMERIC(12,2) holds at most 10 integer digits: |value| <= 9,999,999,999.99. */
    private static final BigDecimal LIMIT = BigDecimal.TEN.pow(10);

    private Money() {
    }

    /**
     * Returns the value with scale exactly 2. Fewer decimal places are padded ({@code 66.4} becomes
     * {@code 66.40}); more are rejected rather than rounded, because Postgres would otherwise round
     * them silently on insert.
     *
     * @throws IllegalArgumentException if the value has more than two decimal places or does not
     *                                  fit NUMERIC(12,2)
     */
    public static BigDecimal requireNumeric12x2(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        BigDecimal scaled;
        try {
            scaled = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(name + " has more than two decimal places", e);
        }
        if (scaled.abs().compareTo(LIMIT) >= 0) {
            throw new IllegalArgumentException(name + " does not fit NUMERIC(12,2)");
        }
        return scaled;
    }
}

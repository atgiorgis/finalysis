package com.finalysis.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one place analytics divides (docs/plans/phase-5.md §4): SQL sums and counts exactly, Java divides in
 * {@code BigDecimal} with {@code HALF_EVEN}. Money has scale 2, percents scale 1, multiples scale 2.
 */
public final class Arithmetic {

    public static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Arithmetic() {
    }

    /**
     * {@code part / whole × 100}, one decimal, {@code HALF_EVEN}; null when {@code whole} is 0, so a share of nothing
     * is never shown as 0.0.
     */
    public static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole.signum() == 0) {
            return null;
        }
        // Multiply first: the only rounding is the final one.
        return part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_EVEN);
    }

    /** The mean of {@code count} amounts that sum to {@code sum}: money, scale 2, {@code HALF_EVEN}. */
    public static BigDecimal average(BigDecimal sum, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("an average needs at least one value");
        }
        return sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_EVEN);
    }

    static BigDecimal percent(int part, int whole) {
        return percent(BigDecimal.valueOf(part), BigDecimal.valueOf(whole));
    }

    /** {@code part / whole}, a multiple: scale 2, {@code HALF_EVEN} ("13.55 times the median"). */
    public static BigDecimal ratio(BigDecimal part, BigDecimal whole) {
        return part.divide(whole, 2, RoundingMode.HALF_EVEN);
    }

    /** Money rounded to cents, {@code HALF_EVEN}: a threshold computed from a rounded baseline (§6.6). */
    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_EVEN);
    }
}

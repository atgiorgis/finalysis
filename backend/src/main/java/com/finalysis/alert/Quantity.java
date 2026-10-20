package com.finalysis.alert;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A number with its unit, as JSON text (docs/plans/phase-5.md §6.6), so the basis of an alert says what each figure is
 * without a field per unit. Built only through the factories, which fix the scale: money and multiples 2 decimals,
 * percents 1. A value with more decimals than its unit allows is a bug, never rounded here.
 */
public record Quantity(QuantityUnit unit, String value) {

    public static Quantity money(BigDecimal amount) {
        return new Quantity(QuantityUnit.MONEY, amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
    }

    public static Quantity percent(BigDecimal percent) {
        return new Quantity(QuantityUnit.PERCENT, percent.setScale(1, RoundingMode.UNNECESSARY).toPlainString());
    }

    public static Quantity ratio(BigDecimal ratio) {
        return new Quantity(QuantityUnit.RATIO, ratio.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
    }

    public static Quantity count(int count) {
        return new Quantity(QuantityUnit.COUNT, Integer.toString(count));
    }

    public static Quantity days(int days) {
        return new Quantity(QuantityUnit.DAYS, Integer.toString(days));
    }
}

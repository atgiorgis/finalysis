package com.finalysis.ingestion.csv;

import com.finalysis.ingestion.Money;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an amount cell of a CSV export: an optional leading minus or enclosing parentheses for a
 * negative, an optional {@code $}, digits with optional comma thousands separators in groups of
 * three, and up to two decimal places. Surrounding whitespace is ignored for parsing only; the cell
 * itself is kept as written ({@code printed_amount}).
 *
 * <p>Accepted: {@code 1234.5}, {@code -1,234.56}, {@code $12.00}, {@code -$12.00}, {@code (12.00)},
 * {@code ($1,234.56)}. Rejected: more than two decimals (never rounded), {@code 12.}, {@code .50},
 * {@code +12}, {@code 1,23}, {@code -(12)}, unbalanced parentheses, other currency symbols.
 */
final class CsvAmount {

    private static final Pattern AMOUNT = Pattern.compile(
            "(?<open>\\()?(?<minus>-)?\\$?(?<whole>[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.(?<fraction>[0-9]+))?(?<close>\\))?");

    private CsvAmount() {
    }

    /**
     * @throws IllegalArgumentException with a reason that never quotes the cell, if it is not an
     *                                  amount, has more than two decimal places or does not fit
     *                                  NUMERIC(12,2)
     */
    static BigDecimal parse(String cell) {
        Matcher m = AMOUNT.matcher(cell.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("is not an amount");
        }
        boolean parentheses = m.group("open") != null;
        if (parentheses != (m.group("close") != null) || parentheses && m.group("minus") != null) {
            throw new IllegalArgumentException("is not an amount");
        }
        String fraction = m.group("fraction");
        if (fraction != null && fraction.length() > 2) {
            throw new IllegalArgumentException("has more than two decimal places");
        }
        BigDecimal value = new BigDecimal(m.group("whole").replace(",", "") + (fraction == null ? "" : "." + fraction));
        if (parentheses || m.group("minus") != null) {
            value = value.negate();
        }
        try {
            return Money.requireNumeric12x2(value, "amount");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("does not fit NUMERIC(12,2)");
        }
    }
}

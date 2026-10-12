package com.finalysis.ingestion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything a parser read from one statement file. Validated here so that every parser's output
 * meets the same rules before anything is saved.
 *
 * @param openingBalance null when the format doesn't print one
 * @param closingBalance null when the format doesn't print one
 * @param sectionTotals  totals as printed per section (ING-10); empty when the format prints none
 * @param warnings       for the import summary; must never contain file contents, only line
 *                       numbers, column names and counts (SEC-10)
 */
public record ParsedStatement(AccountHints hints, LocalDate periodStart, LocalDate periodEnd,
                              BigDecimal openingBalance, BigDecimal closingBalance,
                              Map<SourceSection, BigDecimal> sectionTotals,
                              List<ParsedTxn> txns, List<String> warnings) {

    public ParsedStatement {
        Objects.requireNonNull(hints, "hints");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("periodEnd is before periodStart");
        }
        if (openingBalance != null) {
            openingBalance = Money.requireNumeric12x2(openingBalance, "openingBalance");
        }
        if (closingBalance != null) {
            closingBalance = Money.requireNumeric12x2(closingBalance, "closingBalance");
        }
        sectionTotals = validSectionTotals(sectionTotals);
        txns = List.copyOf(txns);
        warnings = List.copyOf(warnings);
    }

    /** Period and counts only: balances, rows and warnings come from the file (SEC-10). */
    @Override
    public String toString() {
        return "ParsedStatement[" + periodStart + " to " + periodEnd + ", " + txns.size() + " txns, "
                + warnings.size() + " warnings]";
    }

    /**
     * Keys are {@link SourceSection} values by type; amounts must be non-null, scale 2 and within
     * NUMERIC(12,2) (ING-10). Returns an unmodifiable copy with scale-2 amounts.
     */
    static Map<SourceSection, BigDecimal> validSectionTotals(Map<SourceSection, BigDecimal> totals) {
        Map<SourceSection, BigDecimal> valid = new EnumMap<>(SourceSection.class);
        totals.forEach((section, total) -> valid.put(
                Objects.requireNonNull(section, "section total key"),
                Money.requireNumeric12x2(total, "section total for " + section)));
        return Map.copyOf(valid);
    }
}

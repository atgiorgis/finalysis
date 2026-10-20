package com.finalysis.analytics;

import com.finalysis.api.ApiMoney;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One monthly series (ANA-07, docs/plans/phase-5.md §6.4, §6.5): an account and cleaned merchant's latest run of at
 * least {@code min-occurrences} charges. Amounts are magnitudes, positive = money out (§4).
 *
 * @param merchant         the cleaned merchant, exactly as stored
 * @param category         the category of most of its charges (ties: the latest charge's); null when that is none
 * @param typicalAmount    the median charge ({@code percentile_disc(0.5)}: an actual charge, the lower middle one of an
 *                         even count), so it can lag a recent price change; see {@code lastAmount}
 * @param lastAmount       the latest charge
 * @param fixedPrice       the last {@code stable-count} + 1 charges are equal (variable bills are listed with false)
 * @param occurrences      charges in the run
 * @param nextExpectedDate {@code lastDate} plus one calendar month: a projection, compared with nothing in Phase 5
 * @param status           {@code ACTIVE} while {@code lastDate} is at most {@code active-days} before the account's last
 *                         covered day
 */
public record RecurringCharge(long accountId, String merchant, CategoryRef category, Cadence cadence,
                              @ApiMoney BigDecimal typicalAmount, @ApiMoney BigDecimal lastAmount, boolean fixedPrice,
                              int occurrences, LocalDate firstDate, LocalDate lastDate, LocalDate nextExpectedDate,
                              RecurringStatus status) {
}

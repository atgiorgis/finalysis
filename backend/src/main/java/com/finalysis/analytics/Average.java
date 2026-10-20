package com.finalysis.analytics;

import com.finalysis.api.ApiMoney;
import java.math.BigDecimal;

/**
 * A trailing average (docs/plans/phase-5.md §7): the mean over the {@code COMPLETE} months among the
 * {@code monthsWanted} months before the month, scale 2, {@code HALF_EVEN}. A complete month without rows counts as
 * 0.00; a month that isn't complete is skipped, and the window doesn't reach further back to make up for it.
 *
 * @param amount     same measure as the row; null when {@code monthsUsed} is 0
 * @param monthsUsed how many complete months the mean is over
 */
public record Average(@ApiMoney BigDecimal amount, int monthsUsed, int monthsWanted) {
}

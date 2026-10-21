package com.finalysis.chat.tool;

import com.finalysis.analytics.MonthFlagType;
import java.time.YearMonth;

/**
 * A data-quality flag of a month a tool's result used (docs/plans/phase-6.md §3.1): returned to the client as
 * {@code dataNotes} whether or not the model mentions it, and sent to the model with the account as an alias.
 *
 * @param month null for an alert's flags, which cover the months its figures used
 */
public record DataNote(YearMonth month, MonthFlagType type, long accountId) {
}

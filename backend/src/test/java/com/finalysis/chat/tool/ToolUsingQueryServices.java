package com.finalysis.chat.tool;

import com.finalysis.alert.AlertListResponse;
import com.finalysis.alert.AlertQueryService;
import com.finalysis.analytics.DrillDownFilter;
import com.finalysis.analytics.DrillDownResponse;
import com.finalysis.analytics.DrillDownService;
import com.finalysis.analytics.MonthlyTotalsResponse;
import com.finalysis.analytics.MonthlyTotalsService;
import com.finalysis.analytics.TxnBucket;
import java.time.YearMonth;
import java.util.Optional;

/** A rule fixture (ChatReadOnlyTest): what a tool may do, call the query services and read their records. */
public class ToolUsingQueryServices {

    Object read(MonthlyTotalsService months, DrillDownService drillDown, AlertQueryService alerts) {
        MonthlyTotalsResponse totals = months.months(Optional.of(YearMonth.of(2026, 8)), Optional.empty(), Optional.empty());
        DrillDownResponse rows = drillDown.transactions(new DrillDownFilter(Optional.of(YearMonth.of(2026, 8)),
                Optional.empty(), Optional.empty(), Optional.of(TxnBucket.EXPENSE), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), false, 0, 25));
        AlertListResponse list = alerts.alerts(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                false, 20);
        return totals.months().size() + rows.items().size() + list.items().size();
    }
}

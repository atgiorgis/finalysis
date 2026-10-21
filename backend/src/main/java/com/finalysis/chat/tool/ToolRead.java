package com.finalysis.chat.tool;

import com.finalysis.alert.AlertListResponse;
import com.finalysis.analytics.AccountTotalsResponse;
import com.finalysis.analytics.CategoryTotalsResponse;
import com.finalysis.analytics.ComparisonResponse;
import com.finalysis.analytics.CoverageResponse;
import com.finalysis.analytics.DrillDownResponse;
import com.finalysis.analytics.MerchantTotalsResponse;
import com.finalysis.analytics.MonthlyTotalsResponse;
import com.finalysis.analytics.RecurringChargesResponse;
import com.finalysis.analytics.TrendsResponse;

/**
 * What a tool read: the Phase 5 service's own record, unchanged. It never leaves the machine as is: the executor hands
 * it to the outbound projection, one view per kind (docs/plans/phase-6.md §12.1), and the projection's switch over this
 * sealed type makes a tool without a view a compile error.
 */
public sealed interface ToolRead {

    record MonthTotals(MonthlyTotalsResponse response) implements ToolRead {
    }

    record CategoryTotals(CategoryTotalsResponse response) implements ToolRead {
    }

    record AccountTotals(AccountTotalsResponse response) implements ToolRead {
    }

    record TopMerchants(MerchantTotalsResponse response) implements ToolRead {
    }

    record Trends(TrendsResponse response) implements ToolRead {
    }

    record Comparison(ComparisonResponse response) implements ToolRead {
    }

    /** @param limit     how many rows the model asked for; the page holds as many at most
     * @param accountId the account it was filtered to, or null
     */
    record Transactions(DrillDownResponse response, int limit, Long accountId) implements ToolRead {
    }

    record Recurring(RecurringChargesResponse response) implements ToolRead {
    }

    record Alerts(AlertListResponse response) implements ToolRead {
    }

    record Coverage(CoverageResponse response) implements ToolRead {
    }
}

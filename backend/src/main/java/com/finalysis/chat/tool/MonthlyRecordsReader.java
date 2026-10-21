package com.finalysis.chat.tool;

import com.finalysis.alert.AlertQueryService;
import com.finalysis.analytics.Coverage;
import com.finalysis.analytics.MonthlyTotalsResponse;
import com.finalysis.analytics.MonthlyTotalsService;
import com.finalysis.analytics.RecurringCharge;
import com.finalysis.analytics.RecurringChargesResponse;
import com.finalysis.analytics.RecurringChargesService;
import com.finalysis.analytics.RecurringStatusFilter;
import com.finalysis.analytics.TrendsService;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The monthly summary's records (ANA-05, docs/plans/phase-6.md §11.1), from the Phase 5 query services only, read as the
 * tools read them and in one read-only, REPEATABLE READ transaction, so the four agree: the month's totals (with data
 * quality), its trends rows, its alerts, and the active recurring charges whose last charge fell in it. No tool loop and
 * no model: the caller projects them like the tools' results.
 */
@Component
public class MonthlyRecordsReader {

    private final MonthlyTotalsService months;
    private final TrendsService trends;
    private final AlertQueryService alerts;
    private final RecurringChargesService recurring;
    private final DataNotesReader notes;

    MonthlyRecordsReader(MonthlyTotalsService months, TrendsService trends, AlertQueryService alerts,
                         RecurringChargesService recurring, DataNotesReader notes) {
        this.months = months;
        this.trends = trends;
        this.alerts = alerts;
        this.recurring = recurring;
        this.notes = notes;
    }

    /**
     * One month's records.
     *
     * @param notes the month's data-quality flags (from its totals) and its alerts' flags, local ids for the client
     */
    public record Records(YearMonth month, ToolRead.MonthTotals totals, ToolRead.Trends trends, ToolRead.Alerts alerts,
                          ToolRead.Recurring recurring, List<DataNote> notes) {

        public Records {
            notes = List.copyOf(notes);
        }

        /** Whether the month has any statement data at all. */
        public boolean hasData() {
            return totals.response().months().stream().anyMatch(m -> m.coverage() != Coverage.NONE);
        }
    }

    /** The month that a request without one means: the latest complete month, else the latest month with data. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<YearMonth> defaultMonth() {
        MonthlyTotalsResponse latest = months.months(Optional.empty(), Optional.empty(), Optional.empty());
        return Optional.ofNullable(latest.latestCompleteMonth() != null ? latest.latestCompleteMonth()
                : latest.latestMonth());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Records read(YearMonth month) {
        ToolRead.MonthTotals totals = new ToolRead.MonthTotals(months.months(Optional.of(month), Optional.of(month),
                Optional.empty()));
        ToolRead.Trends trend = new ToolRead.Trends(trends.trends(Optional.of(month), Optional.empty(),
                Optional.empty()));
        ToolRead.Alerts alert = new ToolRead.Alerts(alerts.alerts(Optional.of(month), Optional.of(month),
                Optional.empty(), Optional.empty(), false, AlertsTool.MAX_LIMIT));
        RecurringChargesResponse active = recurring.recurring(Optional.empty(), RecurringStatusFilter.ACTIVE,
                RecurringChargesTool.MAX_LIMIT);
        List<RecurringCharge> charged = active.items().stream()
                .filter(c -> c.lastDate() != null && YearMonth.from(c.lastDate()).equals(month)).toList();
        ToolRead.Recurring series = new ToolRead.Recurring(new RecurringChargesResponse(null,
                RecurringStatusFilter.ACTIVE, active.limit(), charged, false));
        List<DataNote> all = new ArrayList<>(notes.notes(totals));
        notes.notes(alert).stream().filter(n -> !all.contains(n)).forEach(all::add);
        return new Records(month, totals, trend, alert, series, all);
    }
}

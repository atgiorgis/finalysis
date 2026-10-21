package com.finalysis.chat.tool;

import com.finalysis.analytics.MonthRange;
import com.finalysis.analytics.MonthTotals;
import com.finalysis.analytics.MonthlyTotalsService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The data-quality flags of the months a tool's result used (docs/plans/phase-6.md §3.1), as the months endpoint has
 * them ({@code dataQuality.flags}): partial coverage, failed and unverified reconciliation. Month and account totals
 * and alerts carry their own; for the other tools they are read from {@link MonthlyTotalsService} over the same months
 * and account, in the tool call's transaction. Recurring series and coverage have none: coverage lists its flagged
 * statements itself, and a series spans its whole history.
 */
@Component
public class DataNotesReader {

    private final MonthlyTotalsService months;

    public DataNotesReader(MonthlyTotalsService months) {
        this.months = months;
    }

    /** Whether a result's own view already carries its flags, so they aren't sent twice. */
    public static boolean carriedByView(ToolRead read) {
        return read instanceof ToolRead.MonthTotals || read instanceof ToolRead.AccountTotals
                || read instanceof ToolRead.Alerts || read instanceof ToolRead.Coverage
                || read instanceof ToolRead.Recurring;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<DataNote> notes(ToolRead read) {
        Set<DataNote> notes = new LinkedHashSet<>();
        switch (read) {
            case ToolRead.MonthTotals m -> fromMonths(m.response().months().stream(), notes);
            case ToolRead.AccountTotals a -> a.response().accounts()
                    .forEach(account -> fromMonths(account.months().stream(), notes));
            case ToolRead.Alerts a -> a.response().items().forEach(alert -> alert.dataFlags()
                    .forEach(f -> notes.add(new DataNote(null, f.type(), f.accountId()))));
            case ToolRead.CategoryTotals c -> read(c.response().range(), c.response().accountId(), notes);
            case ToolRead.TopMerchants m -> read(m.response().range(), m.response().accountId(), notes);
            case ToolRead.Trends t -> read(t.response().month() == null ? null
                    : new MonthRange(t.response().month(), t.response().month()), t.response().accountId(), notes);
            case ToolRead.Comparison c -> {
                read(c.response().a().range(), c.response().accountId(), notes);
                read(c.response().b().range(), c.response().accountId(), notes);
            }
            case ToolRead.Transactions t -> read(t.response().range(), t.accountId(), notes);
            case ToolRead.Recurring ignored -> { }
            case ToolRead.Coverage ignored -> { }
            case ToolRead.Search r -> read(r.range(), null, notes);
            case ToolRead.MerchantTotals r -> read(r.range(), r.accountId(), notes);
        }
        return List.copyOf(notes);
    }

    private void read(MonthRange range, Long accountId, Set<DataNote> notes) {
        if (range != null) {
            fromMonths(months.months(Optional.of(range.from()), Optional.of(range.to()), Optional.ofNullable(accountId))
                    .months().stream(), notes);
        }
    }

    private static void fromMonths(Stream<MonthTotals> months, Set<DataNote> notes) {
        months.forEach(m -> m.dataQuality().flags()
                .forEach(f -> notes.add(new DataNote(m.month(), f.type(), f.accountId()))));
    }
}

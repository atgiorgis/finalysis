package com.finalysis.chat.tool;

import com.finalysis.analytics.CoverageService;
import com.finalysis.analytics.MonthRange;
import java.time.YearMonth;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The default range of the tools a question usually asks without a period ({@code get_alerts} since step 8b,
 * {@code search_transactions} and {@code get_merchant_totals} since step 8c): every month with data, not the
 * analytics default of 6 months. "How much have I spent at the pharmacy?" means all of it; 6 months left April's row
 * out of d-s04 and April's bill out of d-s08 in the first dev run. Runs inside the tool's read-only transaction.
 */
@Component
class DataMonths {

    private final CoverageService coverage;

    DataMonths(CoverageService coverage) {
        this.coverage = coverage;
    }

    /**
     * The range a tool covers when the model gave no {@code from}: from the first month with data to {@code to} (or the
     * latest month), at most {@value ToolParam#MAX_MONTHS} months. Empty without statements, or when {@code to} is
     * before the data starts: the service's own default applies then.
     */
    Optional<MonthRange> everyMonthUpTo(Optional<YearMonth> to) {
        Optional<MonthRange> data = coverage.dataMonths();
        if (data.isEmpty()) {
            return Optional.empty();
        }
        YearMonth end = to.orElse(data.get().to());
        YearMonth earliest = data.get().from();
        if (earliest.isAfter(end)) {
            return Optional.empty();
        }
        YearMonth bound = end.minusMonths(ToolParam.MAX_MONTHS - 1L);
        return Optional.of(new MonthRange(earliest.isBefore(bound) ? bound : earliest, end));
    }

    /**
     * The range the tool reads: the model's, or every month with data when it gave no {@code from} (and no
     * {@code month}). Either end may stay empty, for the service's own default.
     */
    Range resolve(ToolArguments arguments) {
        Optional<YearMonth> from = arguments.from();
        Optional<YearMonth> to = arguments.to();
        if (from.isPresent()) {
            return new Range(from, to, from.flatMap(f -> to.map(t -> new MonthRange(f, t))));
        }
        Optional<MonthRange> all = everyMonthUpTo(to);
        return all.map(r -> new Range(Optional.of(r.from()), Optional.of(r.to()), all))
                .orElseGet(() -> new Range(from, to, Optional.empty()));
    }

    /**
     * @param covered the whole range when both ends are known, so a result with no rows still says what it covered
     */
    record Range(Optional<YearMonth> from, Optional<YearMonth> to, Optional<MonthRange> covered) {
    }
}

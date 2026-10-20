package com.finalysis.analytics;

import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Turns {@code from} and {@code to} into a {@link MonthRange} (docs/plans/phase-5.md §5.1), with the same defaults and
 * bound for the API and, in Phase 6, the chatbot's tools:
 * <ul>
 *   <li>{@code to} defaults to the month of the last covered day (or to {@code from}, if that is later);</li>
 *   <li>{@code from} defaults to {@code default-months} months ending at {@code to};</li>
 *   <li>{@code from ≤ to}, and the range is at most {@code max-months} months long, or the request is a 400.</li>
 * </ul>
 */
@Component
public class MonthRanges {

    private final AnalyticsProperties properties;

    MonthRanges(AnalyticsProperties properties) {
        this.properties = properties;
    }

    /**
     * @param latestMonth the month of the last covered day; empty without statements
     * @return the range; empty when neither end is given and there are no statements
     * @throws InvalidRequestException for {@code from > to} or a range that is too long
     */
    Optional<MonthRange> resolve(Optional<YearMonth> from, Optional<YearMonth> to, Optional<YearMonth> latestMonth) {
        return resolve(from, to, latestMonth, properties.defaultMonths());
    }

    /**
     * As {@link #resolve(Optional, Optional, Optional)}, with {@code defaultMonths} months when {@code from} is omitted
     * (the alerts default to 3, docs/plans/phase-5.md §5.2); the {@code max-months} bound is the same.
     */
    public Optional<MonthRange> resolve(Optional<YearMonth> from, Optional<YearMonth> to,
                                        Optional<YearMonth> latestMonth, int defaultMonths) {
        if (from.isPresent() && to.isPresent() && from.get().isAfter(to.get())) {
            throw invalid("to", "must not be before from");
        }
        Optional<YearMonth> end = to
                .or(() -> latestMonth.map(latest -> from.filter(f -> f.isAfter(latest)).orElse(latest)))
                .or(() -> from);
        if (end.isEmpty()) {
            return Optional.empty();
        }
        MonthRange range = new MonthRange(from.orElse(end.get().minusMonths(defaultMonths - 1)),
                end.get());
        if (range.length() > properties.maxMonths()) {
            throw invalid(to.isPresent() ? "to" : "from", "the range must be at most " + properties.maxMonths()
                    + " months, both ends included" + (to.isPresent() ? "" : "; without to, it ends at " + range.to()));
        }
        return Optional.of(range);
    }

    private static InvalidRequestException invalid(String field, String message) {
        return new InvalidRequestException(List.of(new InvalidField(field, message)));
    }
}

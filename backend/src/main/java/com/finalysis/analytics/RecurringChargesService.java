package com.finalysis.analytics;

import com.finalysis.analytics.MonthlySeries.Charge;
import com.finalysis.analytics.MonthlySeries.Run;
import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recurring charges (ANA-07; docs/plans/phase-5.md §6.4, §6.5): each account and merchant's latest monthly run of at
 * least {@code min-occurrences} charges, from the same detector and settings as the recurring alerts. Read-only, in
 * one REPEATABLE READ transaction, and bounded by {@code limit}: "what am I subscribed to?" for the Phase 7 UI and the
 * Phase 6 chatbot.
 */
@Service
public class RecurringChargesService {

    public static final int DEFAULT_LIMIT = 50;

    private static final Comparator<RecurringCharge> NEWEST_FIRST = Comparator
            .comparing(RecurringCharge::lastDate, Comparator.reverseOrder())
            .thenComparingLong(RecurringCharge::accountId)
            .thenComparing(RecurringCharge::merchant);

    private final RecurringSeriesQuery series;
    private final CoverageQuery coverage;
    private final ReferenceQuery references;
    private final AnalyticsProperties.Recurring settings;

    public RecurringChargesService(RecurringSeriesQuery series, CoverageQuery coverage, ReferenceQuery references,
                                   AnalyticsProperties properties) {
        this.series = series;
        this.coverage = coverage;
        this.references = references;
        this.settings = properties.recurring();
    }

    /**
     * @param accountId only this account's series; empty = every account
     * @param status    {@code ACTIVE} (the usual question), {@code ENDED} or {@code ALL}
     * @param limit     1 to {@code finalysis.analytics.recurring.max-limit}
     * @throws InvalidRequestException for a limit out of bounds
     * @throws com.finalysis.api.NotFoundException for an unknown account
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecurringChargesResponse recurring(Optional<Long> accountId, RecurringStatusFilter status, int limit) {
        int maxLimit = settings.maxLimit();
        if (limit < 1 || limit > maxLimit) {
            throw new InvalidRequestException(List.of(new InvalidField("limit", "must be 1 to " + maxLimit)));
        }
        accountId.ifPresent(references::requireAccount);

        CoverageMap map = coverage.load();
        Map<Long, CategoryRef> categories = references.categories();
        List<RecurringCharge> matching = new ArrayList<>();
        for (Run run : series.latestRuns()) {
            if (run.size() < settings.minOccurrences()
                    || accountId.isPresent() && run.accountId() != accountId.get()) {
                continue;
            }
            RecurringCharge charge = charge(run, map, categories);
            if (status.includes(charge.status())) {
                matching.add(charge);
            }
        }
        matching.sort(NEWEST_FIRST);
        return new RecurringChargesResponse(accountId.orElse(null), status, limit,
                List.copyOf(matching.subList(0, Math.min(limit, matching.size()))), matching.size() > limit);
    }

    private RecurringCharge charge(Run run, CoverageMap map, Map<Long, CategoryRef> categories) {
        Charge last = run.last();
        Long categoryId = mainCategory(run);
        LocalDate lastCovered = map.lastCoveredDay(run.accountId(), LocalDate.MAX);
        boolean active = lastCovered != null
                && ChronoUnit.DAYS.between(last.date(), lastCovered) <= settings.activeDays();
        return new RecurringCharge(run.accountId(), run.merchant(),
                categoryId == null ? null : categories.get(categoryId), Cadence.MONTHLY,
                Run.median(run.charges()), last.amount(), fixedPrice(run), run.size(), run.first().date(),
                last.date(), last.date().plusMonths(1), active ? RecurringStatus.ACTIVE : RecurringStatus.ENDED);
    }

    /** The last {@code stable-count} + 1 charges are equal. */
    private boolean fixedPrice(Run run) {
        int needed = settings.stableCount() + 1;
        if (run.size() < needed) {
            return false;
        }
        BigDecimal price = run.last().amount();
        return run.charges().subList(run.size() - needed, run.size()).stream()
                .allMatch(c -> c.amount().compareTo(price) == 0);
    }

    /** The category of most charges; ties to the latest charge's among them (no category counts as one). */
    private static Long mainCategory(Run run) {
        Map<Long, Integer> counts = new HashMap<>();
        Map<Long, Integer> latest = new HashMap<>();
        List<Charge> charges = run.charges();
        for (int i = 0; i < charges.size(); i++) {
            Long id = charges.get(i).categoryId();
            counts.merge(id, 1, Integer::sum);
            latest.put(id, i);
        }
        Long best = null;
        int bestCount = -1;
        int bestLatest = -1;
        for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
            int count = entry.getValue();
            int position = latest.get(entry.getKey());
            if (count > bestCount || count == bestCount && position > bestLatest) {
                best = entry.getKey();
                bestCount = count;
                bestLatest = position;
            }
        }
        return best;
    }
}

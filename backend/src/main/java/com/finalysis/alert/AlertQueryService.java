package com.finalysis.alert;

import com.finalysis.alert.AlertDismissalQuery.Dismissal;
import com.finalysis.analytics.CoverageService;
import com.finalysis.analytics.MonthFlag;
import com.finalysis.analytics.MonthFlagType;
import com.finalysis.analytics.MonthRange;
import com.finalysis.analytics.MonthRanges;
import com.finalysis.analytics.ReferenceQuery;
import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import com.finalysis.api.NotFoundException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alerts (ANA-04, docs/plans/phase-5.md §6), computed on demand from the data: listing them is a pure read, so this
 * service is safe as a Phase 6 chatbot tool (§8, SEC-03). Every method is read-only, in one REPEATABLE READ
 * transaction, and nothing here depends on {@link AlertDismissalService}, the one writer.
 *
 * <p>Alerts are household-wide: {@code accountId} chooses which to list (those touching the account), it never
 * changes a check (§6.1).
 */
@Service
public class AlertQueryService {

    /** Most txns shown per alert. */
    public static final int MAX_TXNS = 10;

    /** Months listed when {@code from} is omitted, ending at {@code to} (§5.2). */
    public static final int DEFAULT_MONTHS = 3;

    private static final Set<MonthFlagType> DATA_FLAGS =
            Set.of(MonthFlagType.RECONCILIATION_FAILED, MonthFlagType.PARTIAL_COVERAGE);

    private static final Comparator<Alert> ORDER = Comparator.comparing(Alert::month).reversed()
            .thenComparing(Alert::type)
            .thenComparing(Alert::key);

    private final List<AlertCheck> checks;
    private final CoverageService coverage;
    private final MonthRanges ranges;
    private final ReferenceQuery references;
    private final AlertDismissalQuery dismissals;
    private final AlertProperties properties;

    AlertQueryService(List<AlertCheck> checks, CoverageService coverage, MonthRanges ranges,
                      ReferenceQuery references, AlertDismissalQuery dismissals, AlertProperties properties) {
        this.checks = List.copyOf(checks);
        this.coverage = coverage;
        this.ranges = ranges;
        this.references = references;
        this.dismissals = dismissals;
        this.properties = properties;
    }

    /**
     * @param from             first month; default {@code to} − 2
     * @param to               last month; default the month of the last covered day
     * @param type             one check's alerts only; empty = every check
     * @param accountId        only alerts touching that account; empty = all
     * @param includeDismissed list dismissed alerts too (marked {@code dismissed})
     * @param limit            1 to {@code finalysis.alerts.max-results}
     * @throws InvalidRequestException for a bad range or limit
     * @throws NotFoundException       for an unknown account
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AlertListResponse alerts(Optional<YearMonth> from, Optional<YearMonth> to, Optional<AlertType> type,
                                    Optional<Long> accountId, boolean includeDismissed, int limit) {
        if (limit < 1 || limit > properties.maxResults()) {
            throw new InvalidRequestException(List.of(new InvalidField("limit",
                    "must be between 1 and " + properties.maxResults())));
        }
        Optional<MonthRange> range = ranges.resolve(from, to, coverage.latestMonth(), DEFAULT_MONTHS);
        accountId.ifPresent(references::requireAccount);
        Map<AlertType, Integer> counts = new EnumMap<>(AlertType.class);
        for (AlertType t : AlertType.values()) {
            counts.put(t, 0);
        }
        if (range.isEmpty()) {
            return new AlertListResponse(null, accountId.orElse(null), type.orElse(null), includeDismissed, limit,
                    List.of(), counts, false);
        }

        List<Finding> findings = new ArrayList<>();
        for (AlertCheck check : checks) {
            if (type.isEmpty() || type.get() == check.type()) {
                findings.addAll(check.find(range.get()));
            }
        }
        List<Alert> matching = toAlerts(findings.stream()
                .filter(f -> accountId.isEmpty() || f.accountIds().contains(accountId.get()))
                .toList()).stream()
                .filter(a -> includeDismissed || !a.dismissed())
                .sorted(ORDER)
                .toList();
        matching.forEach(a -> counts.merge(a.type(), 1, Integer::sum));
        return new AlertListResponse(range.get(), accountId.orElse(null), type.orElse(null), includeDismissed, limit,
                matching.stream().limit(limit).toList(), counts, matching.size() > limit);
    }

    /**
     * One alert, computed for its key's month only; dismissed or not.
     *
     * @throws InvalidRequestException for a malformed key
     * @throws NotFoundException       when no current alert has that key
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Alert alert(String key) {
        AlertKey parsed = AlertKey.parse(key);
        MonthRange month = new MonthRange(parsed.month(), parsed.month());
        return checks.stream()
                .filter(check -> check.type() == parsed.type())
                .flatMap(check -> check.find(month).stream())
                .filter(finding -> finding.key().equals(key))
                .findFirst()
                .map(finding -> toAlerts(List.of(finding)).getFirst())
                .orElseThrow(() -> new NotFoundException("Alert", key));
    }

    /** The findings as alerts: each with its dismissal and the flags of the months its figures used. */
    private List<Alert> toAlerts(List<Finding> findings) {
        if (findings.isEmpty()) {
            return List.of();
        }
        Map<String, Dismissal> dismissed = dismissals.find(findings.stream().map(Finding::key).toList());
        LocalDate first = findings.stream().map(Finding::usedFrom).min(LocalDate::compareTo).orElseThrow();
        LocalDate last = findings.stream().map(Finding::usedTo).max(LocalDate::compareTo).orElseThrow();
        Map<YearMonth, List<MonthFlag>> flags =
                coverage.householdFlags(new MonthRange(YearMonth.from(first), YearMonth.from(last)));
        return findings.stream().map(f -> {
            Dismissal dismissal = dismissed.get(f.key());
            return new Alert(f.key(), f.type(), f.month(), f.basis(), f.details(), f.txns(), dataFlags(f, flags),
                    dismissal != null, dismissal == null ? null : dismissal.dismissedAt(),
                    dismissal == null ? null : dismissal.note());
        }).toList();
    }

    /** The data flags of the finding's months, oldest month first, each flag once. */
    private static List<MonthFlag> dataFlags(Finding finding, Map<YearMonth, List<MonthFlag>> flags) {
        Set<MonthFlag> found = new LinkedHashSet<>();
        for (YearMonth m = YearMonth.from(finding.usedFrom()); !m.isAfter(YearMonth.from(finding.usedTo()));
             m = m.plusMonths(1)) {
            flags.getOrDefault(m, List.of()).stream().filter(flag -> DATA_FLAGS.contains(flag.type()))
                    .forEach(found::add);
        }
        return List.copyOf(found);
    }
}

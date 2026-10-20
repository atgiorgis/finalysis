package com.finalysis.alert;

import com.finalysis.alert.AlertTxnQuery.Row;
import com.finalysis.analytics.Arithmetic;
import com.finalysis.analytics.Average;
import com.finalysis.analytics.CategoryKind;
import com.finalysis.analytics.MonthRange;
import com.finalysis.analytics.TrendLine;
import com.finalysis.analytics.TrendRow;
import com.finalysis.analytics.TrendsResponse;
import com.finalysis.analytics.TrendsService;
import com.finalysis.analytics.TxnBucket;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Category spike (docs/plans/phase-5.md §6.3, {@code category-spike}): for each expense category and month M,
 * {@code spent(M) ≥ max(baseline × (1 + min-increase-percent / 100), baseline + min-increase-amount)}.
 * <ul>
 *   <li>The baseline is the trends' trailing 3-month average (§7): complete months among M − 1 … M − 3, already rounded
 *       to cents, so the alert and {@code GET /api/analytics/trends} can never disagree. It needs at least
 *       {@code min-baseline-months} months and must be above 0.</li>
 *   <li>The threshold is computed from that rounded baseline and rounded {@code HALF_EVEN} to cents, then compared with
 *       {@code spent} as shown (§6.6).</li>
 *   <li>M may be partial: a partial month can only grow, so a spike already visible is real.</li>
 *   <li>Only categories of kind EXPENSE; Uncategorized rises and falls with AI runs, and a Transfer category marked not
 *       a transfer isn't spending of a kind a trend means anything for.</li>
 * </ul>
 * One alert per category and month, keyed by the category id, so its key doesn't move as the month's amount does.
 */
@Component
class CategorySpikeCheck implements AlertCheck {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final TrendsService trends;
    private final AlertTxnQuery txns;
    private final AlertProperties.CategorySpike settings;

    CategorySpikeCheck(TrendsService trends, AlertTxnQuery txns, AlertProperties properties) {
        this.trends = trends;
        this.txns = txns;
        this.settings = properties.categorySpike();
    }

    @Override
    public AlertType type() {
        return AlertType.CATEGORY_SPIKE;
    }

    @Override
    public List<Finding> find(MonthRange range) {
        List<Finding> findings = new ArrayList<>();
        // One bucket query for the whole range; each month's rows are those of GET /api/analytics/trends?month=.
        for (TrendsResponse month : trends.trends(range)) {
            for (TrendRow row : month.rows()) {
                if (row.line() == TrendLine.CATEGORY && row.bucket() == TxnBucket.EXPENSE
                        && row.category().kind() == CategoryKind.EXPENSE) {
                    evaluate(month.month(), row).ifPresent(findings::add);
                }
            }
        }
        return findings;
    }

    private Optional<Finding> evaluate(YearMonth month, TrendRow row) {
        Average baseline = row.trailing3();
        if (baseline.amount() == null || baseline.monthsUsed() < settings.minBaselineMonths()
                || baseline.amount().signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal threshold = threshold(baseline.amount());
        BigDecimal spent = row.amount();
        if (spent.compareTo(threshold) < 0) {
            return Optional.empty();
        }
        BigDecimal increase = spent.subtract(baseline.amount());
        AlertDetails details = new AlertDetails.CategorySpike(row.category(), spent, baseline, increase,
                Arithmetic.percent(increase, baseline.amount()));
        AlertBasis basis = new AlertBasis(Quantity.money(spent), Quantity.money(baseline.amount()),
                Quantity.money(threshold), month.minusMonths(3).atDay(1), month.minusMonths(1).atEndOfMonth(),
                List.of(new AlertSetting("min-increase-percent", Quantity.percent(settings.minIncreasePercent())),
                        new AlertSetting("min-increase-amount", Quantity.money(settings.minIncreaseAmount())),
                        new AlertSetting("min-baseline-months", Quantity.count(settings.minBaselineMonths()))));

        List<Row> rows = txns.categoryMonth(row.category().id(), month);
        Set<Long> accounts = new LinkedHashSet<>();
        rows.forEach(r -> accounts.add(r.ref().accountId()));
        List<TxnRef> shown = rows.stream().limit(AlertQueryService.MAX_TXNS).map(Row::ref).toList();
        String key = AlertKey.of(type(), month, List.of(Long.toString(row.category().id())));
        return Optional.of(new Finding(key, type(), month, basis, details, shown, accounts,
                month.minusMonths(3).atDay(1), month.atEndOfMonth()));
    }

    /** {@code max(baseline × (100 + percent) / 100, baseline + amount)}, rounded once, {@code HALF_EVEN}. */
    BigDecimal threshold(BigDecimal baseline) {
        BigDecimal byPercent = baseline.multiply(HUNDRED.add(settings.minIncreasePercent())).divide(HUNDRED);
        return Arithmetic.money(byPercent.max(baseline.add(settings.minIncreaseAmount())));
    }
}

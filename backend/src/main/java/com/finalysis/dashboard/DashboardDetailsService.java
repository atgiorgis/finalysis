package com.finalysis.dashboard;

import com.finalysis.analytics.CategoryRef;
import com.finalysis.analytics.CategoryTotalsResponse;
import com.finalysis.analytics.CategoryTotalsService;
import com.finalysis.analytics.Coverage;
import com.finalysis.analytics.CoverageService;
import com.finalysis.analytics.DrillDownFilter;
import com.finalysis.analytics.DrillDownService;
import com.finalysis.analytics.MerchantTotalsResponse;
import com.finalysis.analytics.MerchantTotalsService;
import com.finalysis.analytics.MonthTotals;
import com.finalysis.analytics.MonthlyTotalsService;
import com.finalysis.analytics.TrendLine;
import com.finalysis.analytics.TrendMeasure;
import com.finalysis.analytics.TrendRow;
import com.finalysis.analytics.TrendsService;
import com.finalysis.analytics.TxnBucket;
import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import com.finalysis.dashboard.DashboardDetails.AgainstAverage;
import com.finalysis.dashboard.DashboardDetails.DetailKind;
import com.finalysis.dashboard.DashboardDetails.DrillDown;
import com.finalysis.dashboard.DashboardDetails.TrendCell;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds {@link DashboardDetails} from the query services (docs/plans/phase-7.md §3.2), in one read-only,
 * repeatable-read transaction. The month's figure, its trend and its drill-down come from the same snapshot, so the
 * panel's rows add up to the figure it shows.
 */
@Service
public class DashboardDetailsService {

    static final int TREND_MONTHS = 6;
    static final int TOP_MERCHANTS = 5;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** What the panel is about: exactly one of a category, Uncategorized, or the income line. */
    public enum Line { INCOME }

    private final CoverageService coverage;
    private final MonthlyTotalsService months;
    private final CategoryTotalsService categories;
    private final TrendsService trends;
    private final MerchantTotalsService merchants;
    private final DrillDownService drillDowns;

    public DashboardDetailsService(CoverageService coverage, MonthlyTotalsService months,
                                   CategoryTotalsService categories, TrendsService trends,
                                   MerchantTotalsService merchants, DrillDownService drillDowns) {
        this.coverage = coverage;
        this.months = months;
        this.categories = categories;
        this.trends = trends;
        this.merchants = merchants;
        this.drillDowns = drillDowns;
    }

    /**
     * @throws InvalidRequestException unless exactly one of {@code categoryId}, {@code uncategorized} and {@code line}
     *                                 is given, or for a month after the latest with data
     * @throws com.finalysis.api.NotFoundException for an unknown category
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardDetails details(YearMonth month, Optional<Long> categoryId, boolean uncategorized,
                                    Optional<Line> line) {
        validate(month, categoryId, uncategorized, line);
        YearMonth first = month.minusMonths(TREND_MONTHS - 1);
        List<MonthTotals> window = months.months(Optional.of(first), Optional.of(month), Optional.empty()).months();
        Map<YearMonth, Coverage> coverageByMonth = new LinkedHashMap<>();
        for (YearMonth m = first; !m.isAfter(month); m = m.plusMonths(1)) {
            coverageByMonth.put(m, Coverage.NONE);
        }
        window.forEach(t -> coverageByMonth.put(t.month(), t.coverage()));
        Coverage monthCoverage = coverageByMonth.get(month);

        if (line.isPresent()) {
            return income(month, monthCoverage, window, coverageByMonth);
        }
        if (uncategorized) {
            return uncategorized(month, monthCoverage, coverageByMonth);
        }
        return category(month, monthCoverage, categoryId.orElseThrow(), coverageByMonth);
    }

    private void validate(YearMonth month, Optional<Long> categoryId, boolean uncategorized, Optional<Line> line) {
        List<InvalidField> errors = new ArrayList<>();
        int asked = (categoryId.isPresent() ? 1 : 0) + (uncategorized ? 1 : 0) + (line.isPresent() ? 1 : 0);
        if (asked != 1) {
            errors.add(new InvalidField("categoryId", "give exactly one of categoryId, uncategorized=true or line=INCOME"));
        }
        YearMonth latest = coverage.coverage().latestMonth();
        if (latest != null && month.isAfter(latest)) {
            errors.add(new InvalidField("month", "must not be after the latest month with data, " + latest));
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
    }

    private DashboardDetails category(YearMonth month, Coverage monthCoverage, long categoryId,
                                      Map<YearMonth, Coverage> coverageByMonth) {
        CategoryTotalsResponse response = categories.categories(Optional.of(month.minusMonths(TREND_MONTHS - 1)),
                Optional.of(month), Optional.empty(), Optional.of(categoryId), false);
        // Refunds & Rebates counts in the REFUND bucket; every other spending category in EXPENSE (ANA-01).
        TxnBucket bucket = response.rows().stream().anyMatch(r -> r.bucket() == TxnBucket.REFUND)
                ? TxnBucket.REFUND : TxnBucket.EXPENSE;
        Optional<CategoryTotalsResponse.Row> row = response.rows().stream().filter(r -> r.bucket() == bucket)
                .findFirst();

        // The month's own row (its share is of the month's spending, not the range's).
        CategoryTotalsResponse.Row monthRow = categories.categories(Optional.of(month), Optional.of(month),
                        Optional.empty(), Optional.of(categoryId), false).rows().stream()
                .filter(r -> r.bucket() == bucket).findFirst().orElse(null);
        BigDecimal total = monthRow == null ? zero() : monthRow.total().net();

        Map<YearMonth, BigDecimal> spent = cells(row.map(r -> r.months()).orElse(List.of()), c -> c.flow().net().negate());
        TrendRow trendRow = trends.trends(Optional.of(month), Optional.empty(), Optional.of(categoryId)).rows().stream()
                .filter(r -> r.line() == TrendLine.CATEGORY && r.bucket() == bucket).findFirst().orElse(null);
        // Named from its rows in the six months, else from the trends (any rows in the 12 months before); a category
        // with none at all has only the id the caller already holds.
        CategoryRef category = row.map(CategoryTotalsResponse.Row::category)
                .orElse(trendRow == null ? null : trendRow.category());
        MerchantTotalsResponse top = merchants.merchants(Optional.of(month), Optional.of(month), Optional.empty(),
                Optional.of(categoryId), TOP_MERCHANTS);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("month", month.toString());
        params.put("bucket", bucket.name());
        params.put("categoryId", Long.toString(categoryId));
        return new DashboardDetails(month, monthCoverage, DetailKind.CATEGORY, category, bucket, total,
                monthRow == null ? null : monthRow.spendingSharePercent(), total.negate(), against(total.negate(),
                trendRow), trend(coverageByMonth, spent), top, drillDown(params, month, Optional.of(bucket),
                Optional.empty(), Optional.of(categoryId)));
    }

    private DashboardDetails uncategorized(YearMonth month, Coverage monthCoverage,
                                           Map<YearMonth, Coverage> coverageByMonth) {
        Optional<CategoryTotalsResponse.Row> row = categories.categories(Optional.of(month.minusMonths(TREND_MONTHS - 1)),
                        Optional.of(month), Optional.empty(), Optional.empty(), true).rows().stream()
                .filter(r -> r.bucket() == TxnBucket.UNCATEGORIZED).findFirst();
        Map<YearMonth, BigDecimal> spent = cells(row.map(r -> r.months()).orElse(List.of()),
                c -> c.flow().moneyOut().negate());
        BigDecimal total = row.flatMap(r -> r.months().stream().filter(c -> c.month().equals(month)).findFirst())
                .map(c -> c.flow().moneyOut()).orElse(zero());
        BigDecimal share = categories.categories(Optional.of(month), Optional.of(month), Optional.empty(),
                        Optional.empty(), true).rows().stream()
                .filter(r -> r.bucket() == TxnBucket.UNCATEGORIZED).findFirst()
                .map(CategoryTotalsResponse.Row::spendingSharePercent).orElse(null);
        TrendRow trendRow = line(month, TrendLine.UNCATEGORIZED);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("month", month.toString());
        params.put("bucket", TxnBucket.UNCATEGORIZED.name());
        params.put("measure", TrendMeasure.SPENT.name());
        return new DashboardDetails(month, monthCoverage, DetailKind.UNCATEGORIZED, null, TxnBucket.UNCATEGORIZED,
                total, share, total.negate(), against(total.negate(), trendRow), trend(coverageByMonth, spent), null,
                drillDown(params, month, Optional.of(TxnBucket.UNCATEGORIZED), Optional.of(TrendMeasure.SPENT),
                        Optional.empty()));
    }

    private DashboardDetails income(YearMonth month, Coverage monthCoverage, List<MonthTotals> window,
                                    Map<YearMonth, Coverage> coverageByMonth) {
        Map<YearMonth, BigDecimal> received = new LinkedHashMap<>();
        window.forEach(t -> received.put(t.month(), t.income()));
        BigDecimal total = received.getOrDefault(month, zero());
        TrendRow trendRow = line(month, TrendLine.TOTAL_INCOME);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("month", month.toString());
        params.put("measure", TrendMeasure.RECEIVED.name());
        return new DashboardDetails(month, monthCoverage, DetailKind.INCOME, null, TxnBucket.INCOME, total, null,
                total, against(total, trendRow), trend(coverageByMonth, received), null,
                drillDown(params, month, Optional.empty(), Optional.of(TrendMeasure.RECEIVED), Optional.empty()));
    }

    private TrendRow line(YearMonth month, TrendLine line) {
        return trends.trends(Optional.of(month), Optional.empty(), Optional.empty()).rows().stream()
                .filter(r -> r.line() == line).findFirst().orElse(null);
    }

    private static Map<YearMonth, BigDecimal> cells(List<CategoryTotalsResponse.Cell> cells,
                                                    Function<CategoryTotalsResponse.Cell, BigDecimal> magnitude) {
        Map<YearMonth, BigDecimal> out = new LinkedHashMap<>();
        cells.forEach(c -> out.put(c.month(), magnitude.apply(c)));
        return out;
    }

    private static List<TrendCell> trend(Map<YearMonth, Coverage> coverageByMonth, Map<YearMonth, BigDecimal> amounts) {
        return coverageByMonth.entrySet().stream()
                .map(e -> new TrendCell(e.getKey(), e.getValue(), amounts.getOrDefault(e.getKey(), zero()))).toList();
    }

    /**
     * The month against the trends' trailing 3-month average: the difference, and its percent of the average,
     * {@code part × 100 / whole} with one rounding, {@code HALF_EVEN} (the analytics rule).
     */
    static AgainstAverage against(BigDecimal amount, TrendRow row) {
        if (row == null || row.trailing3() == null) {
            return new AgainstAverage(null, 0, 3, null, null);
        }
        BigDecimal average = row.trailing3().amount();
        if (average == null) {
            return new AgainstAverage(null, row.trailing3().monthsUsed(), row.trailing3().monthsWanted(), null, null);
        }
        BigDecimal difference = amount.subtract(average);
        BigDecimal percent = average.signum() == 0 ? null
                : difference.multiply(HUNDRED).divide(average.abs(), 1, RoundingMode.HALF_EVEN);
        return new AgainstAverage(average, row.trailing3().monthsUsed(), row.trailing3().monthsWanted(), difference,
                percent);
    }

    private DrillDown drillDown(Map<String, String> params, YearMonth month, Optional<TxnBucket> bucket,
                                Optional<TrendMeasure> measure, Optional<Long> categoryId) {
        DrillDownFilter filter = new DrillDownFilter(Optional.of(month), Optional.empty(), Optional.empty(), bucket,
                measure, Optional.empty(), categoryId, Optional.empty(), false, 0, 1);
        return new DrillDown(DrillDown.PATH, params, drillDowns.transactions(filter).total());
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }
}

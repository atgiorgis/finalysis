package com.finalysis.analytics;

import java.time.YearMonth;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only analytics (docs/plans/phase-5.md §5). Maps HTTP onto the services, which hold the validation, defaults
 * and bounds, so Phase 6's tools get the same rules. Months are {@code yyyy-MM}.
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final MonthlyTotalsService months;
    private final CategoryTotalsService categories;
    private final AccountTotalsService accounts;
    private final CoverageService coverage;
    private final MerchantTotalsService merchants;
    private final TrendsService trends;
    private final DrillDownService drillDown;
    private final RecurringChargesService recurring;

    public AnalyticsController(MonthlyTotalsService months, CategoryTotalsService categories,
                               AccountTotalsService accounts, CoverageService coverage,
                               MerchantTotalsService merchants, TrendsService trends, DrillDownService drillDown,
                               RecurringChargesService recurring) {
        this.months = months;
        this.categories = categories;
        this.accounts = accounts;
        this.coverage = coverage;
        this.merchants = merchants;
        this.trends = trends;
        this.drillDown = drillDown;
        this.recurring = recurring;
    }

    @GetMapping("/months")
    public MonthlyTotalsResponse months(@RequestParam(required = false) YearMonth from,
                                        @RequestParam(required = false) YearMonth to,
                                        @RequestParam(required = false) Long accountId) {
        return months.months(Optional.ofNullable(from), Optional.ofNullable(to), Optional.ofNullable(accountId));
    }

    @GetMapping("/categories")
    public CategoryTotalsResponse categories(@RequestParam(required = false) YearMonth from,
                                             @RequestParam(required = false) YearMonth to,
                                             @RequestParam(required = false) Long accountId,
                                             @RequestParam(required = false) Long categoryId,
                                             @RequestParam(defaultValue = "false") boolean uncategorized) {
        return categories.categories(Optional.ofNullable(from), Optional.ofNullable(to),
                Optional.ofNullable(accountId), Optional.ofNullable(categoryId), uncategorized);
    }

    @GetMapping("/accounts")
    public AccountTotalsResponse accounts(@RequestParam(required = false) YearMonth from,
                                          @RequestParam(required = false) YearMonth to) {
        return accounts.accounts(Optional.ofNullable(from), Optional.ofNullable(to));
    }

    @GetMapping("/coverage")
    public CoverageResponse coverage() {
        return coverage.coverage();
    }

    @GetMapping("/merchants")
    public MerchantTotalsResponse merchants(@RequestParam(required = false) YearMonth from,
                                            @RequestParam(required = false) YearMonth to,
                                            @RequestParam(required = false) Long accountId,
                                            @RequestParam(required = false) Long categoryId,
                                            @RequestParam(defaultValue = "" + MerchantTotalsService.DEFAULT_LIMIT)
                                            int limit) {
        return merchants.merchants(Optional.ofNullable(from), Optional.ofNullable(to),
                Optional.ofNullable(accountId), Optional.ofNullable(categoryId), limit);
    }

    @GetMapping("/trends")
    public TrendsResponse trends(@RequestParam(required = false) YearMonth month,
                                 @RequestParam(required = false) Long accountId,
                                 @RequestParam(required = false) Long categoryId) {
        return trends.trends(Optional.ofNullable(month), Optional.ofNullable(accountId),
                Optional.ofNullable(categoryId));
    }

    /** The txns behind a total; see {@link DrillDownFilter} for the filters. */
    @GetMapping("/transactions")
    public DrillDownResponse transactions(@RequestParam(required = false) YearMonth month,
                                          @RequestParam(required = false) YearMonth from,
                                          @RequestParam(required = false) YearMonth to,
                                          @RequestParam(required = false) TxnBucket bucket,
                                          @RequestParam(required = false) TrendMeasure measure,
                                          @RequestParam(required = false) Long accountId,
                                          @RequestParam(required = false) Long categoryId,
                                          @RequestParam(required = false) String merchant,
                                          @RequestParam(defaultValue = "false") boolean noMerchant,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "" + DrillDownFilter.DEFAULT_SIZE) int size) {
        return drillDown.transactions(new DrillDownFilter(Optional.ofNullable(month), Optional.ofNullable(from),
                Optional.ofNullable(to), Optional.ofNullable(bucket), Optional.ofNullable(measure),
                Optional.ofNullable(accountId), Optional.ofNullable(categoryId), Optional.ofNullable(merchant),
                noMerchant, page, size));
    }

    @GetMapping("/recurring")
    public RecurringChargesResponse recurring(@RequestParam(required = false) Long accountId,
                                              @RequestParam(defaultValue = "ACTIVE") RecurringStatusFilter status,
                                              @RequestParam(defaultValue = "" + RecurringChargesService.DEFAULT_LIMIT)
                                              int limit) {
        return recurring.recurring(Optional.ofNullable(accountId), status, limit);
    }
}

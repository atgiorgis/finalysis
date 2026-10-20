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

    public AnalyticsController(MonthlyTotalsService months, CategoryTotalsService categories,
                               AccountTotalsService accounts, CoverageService coverage) {
        this.months = months;
        this.categories = categories;
        this.accounts = accounts;
        this.coverage = coverage;
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
}

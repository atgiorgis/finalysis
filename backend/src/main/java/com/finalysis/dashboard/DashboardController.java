package com.finalysis.dashboard;

import java.time.YearMonth;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The dashboard's reads (docs/plans/phase-7.md §3). Read-only; months are {@code yyyy-MM}. */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboard;
    private final DashboardDetailsService details;

    public DashboardController(DashboardService dashboard, DashboardDetailsService details) {
        this.dashboard = dashboard;
        this.details = details;
    }

    /** The Overview of one month; default the latest month with data. 400 for a month after it. */
    @GetMapping
    public DashboardResponse dashboard(@RequestParam(required = false) YearMonth month) {
        return dashboard.dashboard(Optional.ofNullable(month));
    }

    /**
     * The details panel of one Overview total: a spending category ({@code categoryId}), Uncategorized money out
     * ({@code uncategorized=true}) or the income line ({@code line=INCOME}); exactly one. 400 otherwise or for a month
     * after the latest with data; 404 for an unknown category.
     */
    @GetMapping("/details")
    public DashboardDetails details(@RequestParam YearMonth month, @RequestParam(required = false) Long categoryId,
                                    @RequestParam(defaultValue = "false") boolean uncategorized,
                                    @RequestParam(required = false) DashboardDetailsService.Line line) {
        return details.details(month, Optional.ofNullable(categoryId), uncategorized, Optional.ofNullable(line));
    }
}

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

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    /** The Overview of one month; default the latest month with data. 400 for a month after it. */
    @GetMapping
    public DashboardResponse dashboard(@RequestParam(required = false) YearMonth month) {
        return dashboard.dashboard(Optional.ofNullable(month));
    }
}

package com.finalysis.chat.tool;

import static com.finalysis.chat.tool.Params.ACCOUNT;
import static com.finalysis.chat.tool.Params.MONTH;
import static com.finalysis.chat.tool.Params.TO;

import com.finalysis.alert.AlertQueryService;
import com.finalysis.alert.AlertType;
import com.finalysis.analytics.CoverageService;
import com.finalysis.analytics.MonthRange;
import java.time.YearMonth;
import java.util.Optional;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** {@code get_alerts} over {@code AlertQueryService.alerts} (docs/plans/phase-6.md §3.2). */
@Component
class AlertsTool implements ChatTool {

    static final int MAX_LIMIT = 20;

    private static final ToolParam ALERTS_FROM = ToolParam.optional("from", new ToolParam.Month(),
            "First month of a range, yyyy-MM: \"2026-06\" for June 2026. Default: the first month with data (at most "
                    + ToolParam.MAX_MONTHS + " months before `to`), so every alert is listed. Not with month.");
    private static final ToolParam TYPE = ToolParam.optional("type", new ToolParam.Choice(AlertType.class),
            "Only this kind of alert. Default: every kind.");
    private static final ToolParam INCLUDE_DISMISSED = ToolParam.optional("includeDismissed", new ToolParam.Flag(),
            "true: also alerts the user dismissed (marked dismissed). Default false.");

    private final AlertQueryService alerts;
    private final CoverageService coverage;

    AlertsTool(AlertQueryService alerts, CoverageService coverage) {
        this.alerts = alerts;
        this.coverage = coverage;
    }

    @Override
    public ToolName name() {
        return ToolName.GET_ALERTS;
    }

    @Override
    public String description() {
        return "Unusual activity, newest month first: category spikes, large transactions, possible duplicates, "
                + "recurring price increases and new recurring charges. Each alert has its month, its basis (observed, "
                + "baseline and threshold with units; amounts are positive magnitudes), typed details, up to 5 "
                + "transactions and data flags; counts per type cover every match. Without month or from, it covers "
                + "every month with data (at most " + ToolParam.MAX_MONTHS + "); from and to in the result say which "
                + "months it covered.";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(MONTH, ALERTS_FROM, TO, TYPE, ACCOUNT, INCLUDE_DISMISSED, Params.limit(MAX_LIMIT));
    }

    @Override
    public List<FieldProblem> check(ToolArguments arguments) {
        return arguments.rangeProblems();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToolRead read(ToolArguments arguments) {
        Optional<YearMonth> to = arguments.to();
        Optional<YearMonth> from = arguments.from().or(() -> defaultFrom(to));
        return new ToolRead.Alerts(alerts.alerts(from, to,
                arguments.choice("type", AlertType.class), arguments.id("account"),
                arguments.flag("includeDismissed", false), arguments.number("limit")));
    }

    /**
     * Every month with data, ending at {@code to} (or the latest month), at most {@value ToolParam#MAX_MONTHS} months
     * (step 8b: the service's 3-month default hid older alerts from questions without a period). Empty without
     * statements, or when {@code to} is before the data starts: the service's own default applies then.
     */
    private Optional<YearMonth> defaultFrom(Optional<YearMonth> to) {
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
        return Optional.of(earliest.isBefore(bound) ? bound : earliest);
    }
}

package com.finalysis.chat.tool;

import static com.finalysis.chat.tool.Params.ACCOUNT;
import static com.finalysis.chat.tool.Params.FROM;
import static com.finalysis.chat.tool.Params.TO;

import com.finalysis.analytics.MonthlyTotalsService;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** {@code get_month_totals} over {@code MonthlyTotalsService.months} (docs/plans/phase-6.md §3.2). */
@Component
class MonthTotalsTool implements ChatTool {

    private final MonthlyTotalsService months;

    MonthTotalsTool(MonthlyTotalsService months) {
        this.months = months;
    }

    @Override
    public ToolName name() {
        return ToolName.GET_MONTH_TOTALS;
    }

    @Override
    public String description() {
        return "Each month's household totals: income, spending and net (income + spending), signed money with two "
                + "decimals (negative = money out), the savings rate percent, refunds, uncategorized money in and out, "
                + "unlinked transfers, the month's coverage (COMPLETE, PARTIAL, NONE) and data-quality counts and flags. "
                + "Transfers between the user's own accounts are not income or spending. At most "
                + ToolParam.MAX_MONTHS + " months.";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(FROM, TO, ACCOUNT);
    }

    @Override
    public List<FieldProblem> check(ToolArguments arguments) {
        return arguments.rangeProblems();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToolRead read(ToolArguments arguments) {
        return new ToolRead.MonthTotals(months.months(arguments.month("from"), arguments.month("to"),
                arguments.id("account")));
    }
}

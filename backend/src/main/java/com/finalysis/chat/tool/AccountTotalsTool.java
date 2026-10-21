package com.finalysis.chat.tool;

import static com.finalysis.chat.tool.Params.FROM;
import static com.finalysis.chat.tool.Params.TO;

import com.finalysis.analytics.AccountTotalsService;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** {@code get_account_totals} over {@code AccountTotalsService.accounts} (docs/plans/phase-6.md §3.2). */
@Component
class AccountTotalsTool implements ChatTool {

    private final AccountTotalsService accounts;

    AccountTotalsTool(AccountTotalsService accounts) {
        this.accounts = accounts;
    }

    @Override
    public ToolName name() {
        return ToolName.GET_ACCOUNT_TOTALS;
    }

    @Override
    public String description() {
        return "Each account, by its alias, with the same monthly totals as get_month_totals: income, spending and net "
                + "(signed, negative = money out), coverage and data quality. Never a balance. At most "
                + ToolParam.MAX_MONTHS + " months.";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(FROM, TO);
    }

    @Override
    public List<FieldProblem> check(ToolArguments arguments) {
        return arguments.rangeProblems();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToolRead read(ToolArguments arguments) {
        return new ToolRead.AccountTotals(accounts.accounts(arguments.month("from"), arguments.month("to")));
    }
}

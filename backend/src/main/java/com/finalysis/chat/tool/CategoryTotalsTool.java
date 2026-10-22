package com.finalysis.chat.tool;

import static com.finalysis.chat.tool.Params.ACCOUNT;
import static com.finalysis.chat.tool.Params.CATEGORY;
import static com.finalysis.chat.tool.Params.FROM;
import static com.finalysis.chat.tool.Params.MONTH;
import static com.finalysis.chat.tool.Params.TO;

import com.finalysis.analytics.CategoryTotalsService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** {@code get_category_totals} over {@code CategoryTotalsService.categories} (docs/plans/phase-6.md §3.2). */
@Component
class CategoryTotalsTool implements ChatTool {

    private static final ToolParam UNCATEGORIZED = ToolParam.optional("uncategorized", new ToolParam.Flag(),
            "true: only the Uncategorized row. Not with category. Default false.");

    private final CategoryTotalsService categories;

    CategoryTotalsTool(CategoryTotalsService categories) {
        this.categories = categories;
    }

    @Override
    public ToolName name() {
        return ToolName.GET_CATEGORY_TOTALS;
    }

    @Override
    public String description() {
        return "Totals per bucket and category, per month and over the range: moneyOut (negative), moneyIn "
                + "(positive), net (signed) and the transaction count. Buckets: INCOME, EXPENSE, REFUND, UNCATEGORIZED, "
                + "UNLINKED_TRANSFER, LINKED_TRANSFER; linked transfers move money between the user's own accounts and "
                + "are never spending. With category, that category's rows in every bucket. At most "
                + ToolParam.MAX_MONTHS + " months.";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(MONTH, FROM, TO, ACCOUNT, CATEGORY, UNCATEGORIZED);
    }

    @Override
    public List<FieldProblem> check(ToolArguments arguments) {
        List<FieldProblem> problems = new ArrayList<>(arguments.rangeProblems());
        if (arguments.flag("uncategorized", false)) {
            problems.addAll(arguments.conflict("uncategorized", "category"));
        }
        return problems;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToolRead read(ToolArguments arguments) {
        return new ToolRead.CategoryTotals(categories.categories(arguments.from(), arguments.to(),
                arguments.id("account"), arguments.id("category"), arguments.flag("uncategorized", false)));
    }
}

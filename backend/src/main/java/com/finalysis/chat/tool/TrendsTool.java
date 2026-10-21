package com.finalysis.chat.tool;

import static com.finalysis.chat.tool.Params.ACCOUNT;
import static com.finalysis.chat.tool.Params.CATEGORY;

import com.finalysis.analytics.TrendsService;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** {@code get_trends} over {@code TrendsService.trends(month, accountId, categoryId)} (docs/plans/phase-6.md §3.2). */
@Component
class TrendsTool implements ChatTool {

    private static final ToolParam MONTH = ToolParam.optional("month", new ToolParam.Month(),
            "The month, yyyy-MM. Default: the latest month with data.");

    private final TrendsService trends;

    TrendsTool(TrendsService trends) {
        this.trends = trends;
    }

    @Override
    public ToolName name() {
        return ToolName.GET_TRENDS;
    }

    @Override
    public String description() {
        return "One month against earlier ones, per line (total spending, total income, uncategorized, each category): "
                + "amount as a positive magnitude (measure SPENT = money out after refunds, RECEIVED = money in), the "
                + "previous month, changeAmount and changePercent from it, the trailing 3- and 6-month averages over "
                + "complete months (with how many months counted) and the same month a year earlier.";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(MONTH, ACCOUNT, CATEGORY);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToolRead read(ToolArguments arguments) {
        return new ToolRead.Trends(trends.trends(arguments.month("month"), arguments.id("account"),
                arguments.id("category")));
    }
}

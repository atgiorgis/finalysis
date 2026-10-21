package com.finalysis.chat.tool;

import static com.finalysis.chat.tool.Params.ACCOUNT;
import static com.finalysis.chat.tool.Params.CATEGORY;
import static com.finalysis.chat.tool.Params.FROM;
import static com.finalysis.chat.tool.Params.TO;

import com.finalysis.analytics.MerchantTotalsService;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** {@code get_top_merchants} over {@code MerchantTotalsService.merchants} (docs/plans/phase-6.md §3.2). */
@Component
class TopMerchantsTool implements ChatTool {

    static final int MAX_LIMIT = 20;

    private final MerchantTotalsService merchants;

    TopMerchantsTool(MerchantTotalsService merchants) {
        this.merchants = merchants;
    }

    @Override
    public ToolName name() {
        return ToolName.GET_TOP_MERCHANTS;
    }

    @Override
    public String description() {
        return "The merchants the user spent most at: spent (a positive amount = money out after refunds), the "
                + "number of transactions, first and last date and category; then othersSpent and othersCount for the "
                + "rest, so the list adds up to totalSpent. A merchant shown as MERCHANT WITHHELD is a real merchant "
                + "whose name can't be sent; its amounts still count. At most " + ToolParam.MAX_MONTHS + " months.";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(FROM, TO, ACCOUNT, CATEGORY, Params.limit(MAX_LIMIT));
    }

    @Override
    public List<FieldProblem> check(ToolArguments arguments) {
        return arguments.rangeProblems();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToolRead read(ToolArguments arguments) {
        return new ToolRead.TopMerchants(merchants.merchants(arguments.month("from"), arguments.month("to"),
                arguments.id("account"), arguments.id("category"), arguments.number("limit")));
    }
}

package com.finalysis.chat.tool;

import static com.finalysis.chat.tool.Params.ACCOUNT;
import static com.finalysis.chat.tool.Params.CATEGORY;
import static com.finalysis.chat.tool.Params.MONTH;
import static com.finalysis.chat.tool.Params.TO;

import com.finalysis.analytics.MerchantTotalsService;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code get_top_merchants} over {@code MerchantTotalsService.merchants} (docs/plans/phase-6.md §3.2). Without a period it
 * covers every month with data ({@link DataMonths}, step 8f), as search and merchant totals do, so a merchant's count
 * agrees across the three (d-s05: 17 over the 6-month default against 18 over all data).
 */
@Component
class TopMerchantsTool implements ChatTool {

    static final int MAX_LIMIT = 20;

    private final MerchantTotalsService merchants;
    private final DataMonths dataMonths;

    TopMerchantsTool(MerchantTotalsService merchants, DataMonths dataMonths) {
        this.merchants = merchants;
        this.dataMonths = dataMonths;
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
                + "whose name can't be sent; its amounts still count. Without month or from: every month with data (at most "
                + ToolParam.MAX_MONTHS + "); from and to in the result say which.";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(MONTH, Params.ALL_DATA_FROM, TO, ACCOUNT, CATEGORY, Params.limit(MAX_LIMIT));
    }

    @Override
    public List<FieldProblem> check(ToolArguments arguments) {
        return arguments.rangeProblems();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToolRead read(ToolArguments arguments) {
        DataMonths.Range months = dataMonths.resolve(arguments);
        return new ToolRead.TopMerchants(merchants.merchants(months.from(), months.to(),
                arguments.id("account"), arguments.id("category"), arguments.number("limit")));
    }
}

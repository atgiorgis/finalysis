package com.finalysis.chat.tool;

import static com.finalysis.chat.tool.Params.ACCOUNT;
import static com.finalysis.chat.tool.Params.MONTH;
import static com.finalysis.chat.tool.Params.TO;

import com.finalysis.analytics.MonthRange;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code get_merchant_totals} (docs/plans/phase-6.md §3.2, §4.4): what the merchants the model picked spent over the
 * range, each and combined. The combined figure is the reason the tool exists: a sum written by the model would fail
 * grounding, so Java adds the drill-down totals ({@link MerchantSpendReader}) in {@link BigDecimal}. Without a period it
 * covers every month with data ({@link DataMonths}, step 8c).
 */
@Component
class MerchantTotalsTool implements ChatTool {

    private static final ToolParam MERCHANTS = ToolParam.required("merchants", new ToolParam.Merchants(),
            "1 to " + ToolParam.Merchants.MAX_MERCHANTS + " merchant names, each copied exactly from a result "
                    + "(search_transactions, get_top_merchants, list_transactions).");

    private final MerchantSpendReader spend;
    private final DataMonths dataMonths;

    MerchantTotalsTool(MerchantSpendReader spend, DataMonths dataMonths) {
        this.spend = spend;
        this.dataMonths = dataMonths;
    }

    @Override
    public ToolName name() {
        return ToolName.GET_MERCHANT_TOTALS;
    }

    @Override
    public String description() {
        return "What these merchants spent over the range: per merchant its category, spent (a positive amount = "
                + "money out after refunds) and count, then combined (their sum, computed for you). Use combined for \"how much on all "
                + "of them\"; never add the merchants' figures yourself. Without month or from, it covers every month with data "
                + "(at most " + ToolParam.MAX_MONTHS + "); from and to in the result say which months it covered.";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(MERCHANTS, MONTH, Params.ALL_DATA_FROM, TO, ACCOUNT);
    }

    @Override
    public List<FieldProblem> check(ToolArguments arguments) {
        return arguments.rangeProblems();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToolRead read(ToolArguments arguments) {
        DataMonths.Range months = dataMonths.resolve(arguments);
        List<MerchantSpend> merchants = new ArrayList<>();
        BigDecimal combined = BigDecimal.ZERO.setScale(2);
        int count = 0;
        MonthRange range = months.covered().orElse(null);
        for (MerchantGroup group : arguments.merchants("merchants")) {
            MerchantSpend spent = spend.read(group, months.from(), months.to(), arguments.id("account"));
            merchants.add(spent);
            combined = combined.add(spent.spent());
            count += spent.count();
            range = range == null ? spent.range() : range;
        }
        return new ToolRead.MerchantTotals(range, arguments.id("account").orElse(null), merchants, combined, count);
    }
}

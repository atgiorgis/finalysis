package com.finalysis.chat.tool;

import static com.finalysis.chat.tool.Params.ACCOUNT;
import static com.finalysis.chat.tool.Params.CATEGORY;
import static com.finalysis.chat.tool.Params.MONTH;
import static com.finalysis.chat.tool.Params.TO;

import com.finalysis.analytics.DrillDownFilter;
import com.finalysis.analytics.DrillDownService;
import com.finalysis.analytics.TrendMeasure;
import com.finalysis.analytics.TxnBucket;
import java.util.ArrayList;
import java.util.List;
import java.time.YearMonth;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code list_transactions} over {@code DrillDownService.transactions}, page 0 (docs/plans/phase-6.md §3.2). Without a
 * period it covers every month with data ({@link DataMonths}, step 8f): the prompt sends a search candidate here, and its
 * count must agree with search and merchant totals.
 */
@Component
class ListTransactionsTool implements ChatTool {

    static final int MAX_LIMIT = 25;

    private static final ToolParam BUCKET = ToolParam.optional("bucket", new ToolParam.Choice(TxnBucket.class),
            "Only this bucket. UNCATEGORIZED not with category.");
    private static final ToolParam MEASURE = ToolParam.optional("measure", new ToolParam.Choice(TrendMeasure.class),
            "SPENT: only spending rows (expenses, refunds, uncategorized money out); RECEIVED: only income rows.");
    private static final ToolParam MERCHANT = ToolParam.optional("merchant", new ToolParam.Merchant(),
            "Only this merchant, copied exactly from an earlier result of this turn.");

    private final DrillDownService drillDown;
    private final DataMonths dataMonths;

    ListTransactionsTool(DrillDownService drillDown, DataMonths dataMonths) {
        this.drillDown = drillDown;
        this.dataMonths = dataMonths;
    }

    @Override
    public ToolName name() {
        return ToolName.LIST_TRANSACTIONS;
    }

    @Override
    public String description() {
        return "The transactions behind a total, newest first: total over every match (moneyOut negative, moneyIn "
                + "positive, net, count), then the first rows (ref, date, signed amount, merchant, category, bucket, "
                + "account alias); truncated is true when more matched than were listed. Filters combine with AND. Without month or "
                + "from: every month with data.";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(MONTH, Params.ALL_DATA_FROM, TO, BUCKET, MEASURE, ACCOUNT, CATEGORY, MERCHANT,
                Params.limit(MAX_LIMIT));
    }

    @Override
    public List<FieldProblem> check(ToolArguments arguments) {
        List<FieldProblem> problems = new ArrayList<>(arguments.rangeProblems());
        if (arguments.choice("bucket", TxnBucket.class).filter(b -> b == TxnBucket.UNCATEGORIZED).isPresent()) {
            problems.addAll(arguments.conflict("category", "bucket"));
        }
        return problems;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToolRead read(ToolArguments arguments) {
        int limit = arguments.number("limit");
        Optional<YearMonth> month = arguments.month("month");
        DataMonths.Range months = month.isPresent() ? null : dataMonths.resolve(arguments);
        DrillDownFilter filter = new DrillDownFilter(month, month.isPresent() ? Optional.empty() : months.from(),
                month.isPresent() ? Optional.empty() : months.to(), arguments.choice("bucket", TxnBucket.class),
                arguments.choice("measure", TrendMeasure.class), arguments.id("account"), arguments.id("category"),
                arguments.text("merchant"), false, 0, limit);
        return new ToolRead.Transactions(drillDown.transactions(filter), limit, filter);
    }
}

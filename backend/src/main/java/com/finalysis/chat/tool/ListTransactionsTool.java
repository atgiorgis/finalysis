package com.finalysis.chat.tool;

import static com.finalysis.chat.tool.Params.ACCOUNT;
import static com.finalysis.chat.tool.Params.CATEGORY;
import static com.finalysis.chat.tool.Params.FROM;
import static com.finalysis.chat.tool.Params.TO;

import com.finalysis.analytics.DrillDownFilter;
import com.finalysis.analytics.DrillDownService;
import com.finalysis.analytics.TrendMeasure;
import com.finalysis.analytics.TxnBucket;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** {@code list_transactions} over {@code DrillDownService.transactions}, page 0 (docs/plans/phase-6.md §3.2). */
@Component
class ListTransactionsTool implements ChatTool {

    static final int MAX_LIMIT = 25;

    private static final ToolParam MONTH = ToolParam.optional("month", new ToolParam.Month(),
            "One month, yyyy-MM. Not with from or to.");
    private static final ToolParam BUCKET = ToolParam.optional("bucket", new ToolParam.Choice(TxnBucket.class),
            "Only this bucket. UNCATEGORIZED not with category.");
    private static final ToolParam MEASURE = ToolParam.optional("measure", new ToolParam.Choice(TrendMeasure.class),
            "SPENT: only spending rows (expenses, refunds, uncategorized money out); RECEIVED: only income rows.");
    private static final ToolParam MERCHANT = ToolParam.optional("merchant", new ToolParam.Merchant(),
            "Only this merchant, copied exactly from an earlier result of this turn.");

    private final DrillDownService drillDown;

    ListTransactionsTool(DrillDownService drillDown) {
        this.drillDown = drillDown;
    }

    @Override
    public ToolName name() {
        return ToolName.LIST_TRANSACTIONS;
    }

    @Override
    public String description() {
        return "The transactions behind a total, newest first: total over every match (moneyOut negative, moneyIn "
                + "positive, net, count), then the first rows (ref, date, signed amount, merchant, category, bucket, "
                + "account alias); truncated is true when more matched than were listed. Filters combine with AND.";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(MONTH, FROM, TO, BUCKET, MEASURE, ACCOUNT, CATEGORY, MERCHANT, Params.limit(MAX_LIMIT));
    }

    @Override
    public List<FieldProblem> check(ToolArguments arguments) {
        List<FieldProblem> problems = new ArrayList<>(arguments.conflict("month", "from"));
        problems.addAll(arguments.conflict("month", "to"));
        problems.addAll(arguments.rangeProblems());
        if (arguments.choice("bucket", TxnBucket.class).filter(b -> b == TxnBucket.UNCATEGORIZED).isPresent()) {
            problems.addAll(arguments.conflict("category", "bucket"));
        }
        return problems;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToolRead read(ToolArguments arguments) {
        int limit = arguments.number("limit");
        DrillDownFilter filter = new DrillDownFilter(arguments.month("month"), arguments.month("from"),
                arguments.month("to"), arguments.choice("bucket", TxnBucket.class),
                arguments.choice("measure", TrendMeasure.class), arguments.id("account"), arguments.id("category"),
                arguments.text("merchant"), false, 0, limit);
        return new ToolRead.Transactions(drillDown.transactions(filter), limit, filter);
    }
}

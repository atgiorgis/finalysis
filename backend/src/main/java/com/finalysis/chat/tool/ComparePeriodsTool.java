package com.finalysis.chat.tool;

import static com.finalysis.chat.tool.Params.ACCOUNT;

import com.finalysis.analytics.ComparisonMeasure;
import com.finalysis.analytics.ComparisonService;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** {@code compare_periods} over {@code ComparisonService.compare} (docs/plans/phase-6.md §3.2, §3.3). */
@Component
class ComparePeriodsTool implements ChatTool {

    private static final String PERIOD = "Either {\"month\": \"yyyy-MM\"} or {\"from\": \"yyyy-MM\", \"to\": "
            + "\"yyyy-MM\"}, at most " + ToolParam.MAX_MONTHS + " months.";

    private static final ToolParam MEASURE = ToolParam.required("measure", new ToolParam.Choice(ComparisonMeasure.class),
            "SPENDING, INCOME and NET for the household or an account; CATEGORY_SPENT and CATEGORY_RECEIVED for one "
                    + "category, which then needs category.");
    private static final ToolParam CATEGORY = ToolParam.optional("category", new ToolParam.Category(),
            "The category, exactly as the context block lists it. Only with CATEGORY_SPENT or CATEGORY_RECEIVED.");
    private static final ToolParam A = ToolParam.required("a", new ToolParam.Period(), "The period compared. " + PERIOD);
    private static final ToolParam B = ToolParam.required("b", new ToolParam.Period(),
            "The period it is compared with. " + PERIOD);

    private final ComparisonService comparison;

    ComparePeriodsTool(ComparisonService comparison) {
        this.comparison = comparison;
    }

    @Override
    public ToolName name() {
        return ToolName.COMPARE_PERIODS;
    }

    @Override
    public String description() {
        return "Compares one measure between two periods: each side's value with its months' coverage, the "
                + "difference (a − b) and changePercent (null when b is 0), computed exactly. SPENDING and "
                + "CATEGORY_SPENT are positive amounts (money out after refunds), INCOME and CATEGORY_RECEIVED money "
                + "in, NET is signed (income minus spending). Use it for any difference or percent change; never compute "
                + "one yourself.";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(MEASURE, CATEGORY, ACCOUNT, A, B);
    }

    @Override
    public List<FieldProblem> check(ToolArguments arguments) {
        boolean needs = arguments.choice("measure", ComparisonMeasure.class).orElseThrow().needsCategory();
        if (needs && !arguments.has("category")) {
            return List.of(new FieldProblem("measure", ToolProblem.NEEDS, "category"));
        }
        if (!needs && arguments.has("category")) {
            return List.of(new FieldProblem("category", ToolProblem.CONFLICT, "measure"));
        }
        return List.of();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToolRead read(ToolArguments arguments) {
        return new ToolRead.Comparison(comparison.compare(
                arguments.choice("measure", ComparisonMeasure.class).orElseThrow(), arguments.id("category"),
                arguments.id("account"), arguments.period("a"), arguments.period("b")));
    }
}

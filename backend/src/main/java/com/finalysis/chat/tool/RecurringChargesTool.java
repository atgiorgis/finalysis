package com.finalysis.chat.tool;

import static com.finalysis.chat.tool.Params.ACCOUNT;

import com.finalysis.analytics.RecurringChargesService;
import com.finalysis.analytics.RecurringStatusFilter;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** {@code get_recurring_charges} over {@code RecurringChargesService.recurring} (docs/plans/phase-6.md §3.2). */
@Component
class RecurringChargesTool implements ChatTool {

    static final int MAX_LIMIT = 25;

    private static final ToolParam STATUS = ToolParam.required("status",
            new ToolParam.Choice(RecurringStatusFilter.class),
            "ACTIVE: still charging; ENDED: stopped; ALL: both.");

    private final RecurringChargesService recurring;

    RecurringChargesTool(RecurringChargesService recurring) {
        this.recurring = recurring;
    }

    @Override
    public ToolName name() {
        return ToolName.GET_RECURRING_CHARGES;
    }

    @Override
    public String description() {
        return "Monthly recurring charges (subscriptions and bills) found in the data: merchant, account alias, "
                + "category, typicalAmount and lastAmount (positive = money out), whether the price is fixed, how many "
                + "charges, first and last date, the next expected date and status (ACTIVE or ENDED).";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(ACCOUNT, STATUS, Params.limit(MAX_LIMIT));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToolRead read(ToolArguments arguments) {
        return new ToolRead.Recurring(recurring.recurring(arguments.id("account"),
                arguments.choice("status", RecurringStatusFilter.class).orElseThrow(), arguments.number("limit")));
    }
}

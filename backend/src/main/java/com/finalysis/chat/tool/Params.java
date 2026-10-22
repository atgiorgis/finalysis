package com.finalysis.chat.tool;

/** The parameters several tools share, with one wording (docs/plans/phase-6.md §3.2). */
final class Params {

    /** One month instead of a range (step 8b: models reached for {@code month} on tools that only had a range). */
    static final ToolParam MONTH = ToolParam.optional("month", new ToolParam.Month(),
            "One month, yyyy-MM: \"2026-08\" for August 2026. The same as from and to both that month. Not with from "
                    + "or to.");
    static final ToolParam FROM = ToolParam.optional("from", new ToolParam.Month(),
            "First month of a range, yyyy-MM: \"2026-06\" for June 2026. Default: 6 months ending at `to`; give it "
                    + "to be sure. Not with month.");
    /**
     * {@code from} on the tools whose question usually has no period (alerts since step 8b; search and merchant totals
     * since step 8c): without it they cover every month with data, as {@link DataMonths} works it out.
     */
    static final ToolParam ALL_DATA_FROM = ToolParam.optional("from", new ToolParam.Month(),
            "First month of a range, yyyy-MM: \"2026-06\" for June 2026. Default: the first month with data (at most "
                    + ToolParam.MAX_MONTHS + " months before `to`), so everything is covered. Not with month.");
    static final ToolParam TO = ToolParam.optional("to", new ToolParam.Month(),
            "Last month of a range, yyyy-MM: \"2026-08\" for August 2026. Default: the latest month with data "
                    + "(latestMonth in the context block). At most " + ToolParam.MAX_MONTHS + " months from `from` to "
                    + "`to`, both included. Not with month.");
    static final ToolParam ACCOUNT = ToolParam.optional("account", new ToolParam.Alias(),
            "Only this account, by its alias from the context block (CHECKING-1). Default: every account.");
    static final ToolParam CATEGORY = ToolParam.optional("category", new ToolParam.Category(),
            "Only this category, exactly as the context block lists it. Default: every category.");

    private Params() {
    }

    static ToolParam limit(int max) {
        return ToolParam.required("limit", new ToolParam.WholeNumber(1, max), "How many rows to return, 1 to " + max
                + ".");
    }
}

package com.finalysis.chat.tool;

/** The parameters several tools share, with one wording (docs/plans/phase-6.md §3.2). */
final class Params {

    static final ToolParam FROM = ToolParam.optional("from", new ToolParam.Month(),
            "First month, yyyy-MM. Default: a few months ending at `to`; give it to be sure.");
    static final ToolParam TO = ToolParam.optional("to", new ToolParam.Month(),
            "Last month, yyyy-MM. Default: the latest month with data (latestMonth in the context block). At most "
                    + ToolParam.MAX_MONTHS + " months from `from` to `to`, both included.");
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

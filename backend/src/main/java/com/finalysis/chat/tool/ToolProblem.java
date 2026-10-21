package com.finalysis.chat.tool;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every message a tool error can carry (docs/plans/phase-6.md §3.1): fixed texts, so an error never echoes what the
 * model sent and never carries an exception's message. {@code ChatPayloadGuard} lets only these texts out.
 */
public enum ToolProblem {
    // whole call
    NOT_A_TOOL("no such tool; use one of the tools you were given"),
    NOT_AN_OBJECT("the arguments must be one JSON object"),
    INVALID_ARGUMENTS("one or more arguments are invalid; fix them and call again, or answer without this tool"),
    FAILED("the tool could not read the data; answer without it, or try a narrower question"),
    REFUSED("the result could not be sent safely; answer without it"),
    SKIPPED_LIMIT("not run: this question's tool call limit is reached; answer now from the results you have"),
    CUT("removed from the conversation to keep it within its size limit; call the tool again if you still need it"),
    // one argument
    UNKNOWN_PARAMETER("not a parameter of this tool; use only the schema's properties"),
    REQUIRED("is required"),
    NOT_A_MONTH("must be a month written yyyy-MM, 01 to 12"),
    NOT_AN_ACCOUNT("not an account alias; see the context block"),
    NOT_A_CATEGORY("not a category name; use one of categories exactly as written"),
    NOT_A_LISTED_VALUE("must be one of the values the schema lists"),
    NOT_A_WHOLE_NUMBER("must be a whole number"),
    OUT_OF_BOUNDS("is outside the minimum and maximum the schema gives"),
    NOT_TRUE_OR_FALSE("must be true or false"),
    NOT_TEXT("must be a string"),
    TEXT_TOO_LONG("is too long"),
    NOT_A_PERIOD("must be an object with either month, or from and to"),
    REVERSED_RANGE("must not be after to"),
    RANGE_TOO_LONG("the range is at most 24 months, both ends included"),
    CONFLICT("can't be combined with the parameter named in conflictsWith"),
    NEEDS("needs the parameter named in conflictsWith"),
    NOT_A_MERCHANT("not a merchant name in the data; copy one exactly from a result, or find it with "
            + "search_transactions"),
    WITHHELD_MERCHANT("a withheld name can't be used as a filter; filter by category, month or account instead"),
    SEVERAL_MERCHANTS("this name stands for several stored merchants; get_merchant_totals adds them up"),
    NOT_A_LIST("must be a list of merchant names"),
    LISTED_TWICE("lists the same merchant twice"),
    TEXT_TOO_SHORT("is too short"),
    SEARCH_REFUSED("this search text can't be sent; use other words, without names, numbers or symbols");

    private static final Set<String> TEXTS = Arrays.stream(values()).map(ToolProblem::text)
            .collect(Collectors.toUnmodifiableSet());

    private final String text;

    ToolProblem(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    /** Whether a text is one of these messages, exactly. */
    public static boolean isText(String text) {
        return TEXTS.contains(text);
    }
}

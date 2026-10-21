package com.finalysis.chat.tool;

/**
 * One invalid argument: the parameter's name as the schema has it (never a name the model invented: those are
 * {@link #UNKNOWN}), the problem, and the other parameter a conflict is with.
 */
public record FieldProblem(String name, ToolProblem problem, String conflictsWith) {

    /** The name an unknown parameter is reported under, so the model's own key is never echoed. */
    public static final String UNKNOWN = "(unknown)";

    public FieldProblem(String name, ToolProblem problem) {
        this(name, problem, null);
    }
}

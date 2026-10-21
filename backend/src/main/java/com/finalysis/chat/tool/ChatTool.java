package com.finalysis.chat.tool;

import java.util.List;

/**
 * One read-only chat tool (docs/plans/phase-6.md §3): a thin adapter over one Phase 5 query service method. It declares
 * its parameters (the schema the model sees, and the parsing), checks the combinations a schema can't express, and reads.
 * It never computes a figure and never sees the model's raw text: {@link #read} gets checked {@link ToolArguments}.
 * Only the executor calls {@link #read}, inside a read-only transaction; its result goes through the outbound projection
 * and the guard before any of it is sent (§12, {@code ChatToolPathTest}).
 */
public interface ChatTool {

    ToolName name();

    /** What the model reads: what the tool answers, its units and sign convention. */
    String description();

    List<ToolParam> params();

    /** Problems between parameters (a contradictory filter, a reversed range); none by default. */
    default List<FieldProblem> check(ToolArguments arguments) {
        return List.of();
    }

    /** Reads, with arguments that passed {@link ToolArguments#parse} and {@link #check}. Must be read-only. */
    ToolRead read(ToolArguments arguments);
}

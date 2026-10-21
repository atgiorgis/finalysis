package com.finalysis.chat.grounding;

import com.finalysis.chat.ToolCall;
import com.finalysis.chat.ToolOutcome;
import com.finalysis.chat.tool.RefRegistry;
import java.util.Collection;
import java.util.List;

/**
 * What one turn's answer is checked against (docs/plans/phase-6.md §5.1): that turn's question, its tool calls with
 * their outcomes in order, its refs, and the names the model was given besides the results (the context block's
 * category names and aliases). Figures from earlier turns, the summary or the model's knowledge are not here.
 */
public record TurnEvidence(String question, List<Result> results, RefRegistry refs, Collection<String> names) {

    /** One tool call and how it ended. */
    public record Result(ToolCall call, ToolOutcome outcome) {
    }

    public TurnEvidence {
        results = List.copyOf(results);
        names = List.copyOf(names);
    }
}

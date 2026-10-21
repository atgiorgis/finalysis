package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A tool error the model can read (docs/plans/phase-6.md §3.1): what went wrong, in fixed words, and which parameters.
 * Never the value the model sent, a name it invented or an exception's message.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ToolErrorView(@Outbound(ENUM) String error, @Outbound(MESSAGE) String message, List<Field> fields) {

    /**
     * One parameter: its schema name, the problem, for a conflict the other parameter, and for an unknown category the
     * valid names (as the context block lists them), so the model can correct itself on retry.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Field(@Outbound(PARAM) String name, @Outbound(MESSAGE) String message,
                        @Outbound(PARAM) String conflictsWith, @Outbound(CATEGORY) List<String> categories) {
    }
}

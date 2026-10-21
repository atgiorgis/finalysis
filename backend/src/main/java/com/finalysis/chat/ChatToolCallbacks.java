package com.finalysis.chat;

import com.finalysis.chat.tool.ToolRegistry;
import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

/**
 * The chat tools as Spring AI sees them (docs/plans/phase-6.md §10.1): their definitions (name, description, closed
 * schema) for the model, and callbacks that throw {@link ToolExecutedByFrameworkException} if the framework invokes
 * them. Tool execution is the chat loop's, through {@link ToolExecutor}.
 */
@Component
public class ChatToolCallbacks {

    private final List<ToolCallback> callbacks;

    public ChatToolCallbacks(ToolRegistry registry) {
        this.callbacks = registry.definitions().stream()
                .map(d -> (ToolCallback) new Refusing(new DefaultToolDefinition(d.name(), d.description(),
                        d.inputSchema())))
                .toList();
    }

    public List<ToolCallback> callbacks() {
        return callbacks;
    }

    /** Carries a definition; never runs. */
    static final class Refusing implements ToolCallback {

        private final ToolDefinition definition;

        Refusing(ToolDefinition definition) {
            this.definition = definition;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            throw new ToolExecutedByFrameworkException(definition.name());
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            throw new ToolExecutedByFrameworkException(definition.name());
        }
    }
}

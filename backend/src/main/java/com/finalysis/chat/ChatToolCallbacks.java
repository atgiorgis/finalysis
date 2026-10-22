package com.finalysis.chat;

import com.finalysis.chat.tool.ToolRegistry;
import java.util.List;
import java.util.Map;
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

    private final ToolRegistry registry;
    private final List<ToolCallback> callbacks;
    private volatile Map.Entry<List<ToolRegistry.Definition>, List<ToolCallback>> forCategories =
            Map.entry(List.of(), List.of());

    public ChatToolCallbacks(ToolRegistry registry) {
        this.registry = registry;
        this.callbacks = of(registry.definitions());
    }

    /** The definitions without category names (no enum). */
    public List<ToolCallback> callbacks() {
        return callbacks;
    }

    /** The definitions a turn sends, with the data's category names as an enum ({@link ToolRegistry#definitions(List)}). */
    public List<ToolCallback> callbacks(List<String> categories) {
        List<ToolRegistry.Definition> definitions = registry.definitions(categories);
        Map.Entry<List<ToolRegistry.Definition>, List<ToolCallback>> cached = forCategories;
        if (cached.getKey() != definitions) {
            cached = Map.entry(definitions, of(definitions));
            forCategories = cached;
        }
        return cached.getValue();
    }

    private static List<ToolCallback> of(List<ToolRegistry.Definition> definitions) {
        return definitions.stream()
                .map(d -> (ToolCallback) new Refusing(new DefaultToolDefinition(d.name(), d.description(),
                        d.inputSchema())))
                .toList();
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

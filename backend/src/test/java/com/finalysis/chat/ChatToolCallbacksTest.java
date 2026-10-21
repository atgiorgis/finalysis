package com.finalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.chat.tool.ToolRegistry;
import com.finalysis.chat.tool.ToolRegistryTestSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

/**
 * Prepares step 5's check (docs/plans/phase-6.md §10.1): Spring AI gets the tools' definitions, exactly as the registry
 * has them, and callbacks that throw if the framework runs them.
 */
class ChatToolCallbacksTest {

    private final ToolRegistry registry = ToolRegistryTestSupport.registry();
    private final ChatToolCallbacks callbacks = new ChatToolCallbacks(registry);

    @Test
    void theDefinitionsAreTheRegistrys() {
        assertThat(callbacks.callbacks()).hasSize(com.finalysis.chat.tool.ToolName.values().length);
        for (int i = 0; i < registry.definitions().size(); i++) {
            ToolRegistry.Definition expected = registry.definitions().get(i);
            ToolCallback callback = callbacks.callbacks().get(i);
            assertThat(callback.getToolDefinition().name()).isEqualTo(expected.name());
            assertThat(callback.getToolDefinition().description()).isEqualTo(expected.description());
            assertThat(callback.getToolDefinition().inputSchema()).isEqualTo(expected.inputSchema());
            assertThat(callback.getToolMetadata().returnDirect()).isFalse();
        }
    }

    @Test
    void theFrameworkCanNeverRunATool() {
        for (ToolCallback callback : callbacks.callbacks()) {
            assertThatThrownBy(() -> callback.call("{}"))
                    .isInstanceOf(ToolExecutedByFrameworkException.class)
                    .hasMessageContaining(callback.getToolDefinition().name());
            assertThatThrownBy(() -> callback.call("{\"limit\": 5}", new ToolContext(Map.of())))
                    .isInstanceOf(ToolExecutedByFrameworkException.class);
        }
    }
}

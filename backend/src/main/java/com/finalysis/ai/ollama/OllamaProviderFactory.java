package com.finalysis.ai.ollama;

import com.finalysis.ai.CategorizationProvider;
import com.finalysis.ai.ChatProvider;
import com.finalysis.ai.EmbeddingProvider;
import com.finalysis.ai.OllamaClients;
import com.finalysis.ai.OllamaHostGuard;
import com.finalysis.ai.OllamaProperties;
import com.finalysis.ai.PromptCache;
import com.finalysis.ai.ProviderSet;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.ollama.management.PullModelStrategy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Spring AI's Ollama clients for the Self-hosted and Local paths (docs/plans/phase-9.md §3.2), built here for a path's
 * base URL, never by Spring's auto-configuration: the only code that names an Ollama class ({@code PackageDependencyTest}),
 * and nothing outside {@code com.finalysis.ai} depends on it.
 *
 * <ul>
 *   <li>One model for chat and categorization, one for embeddings ({@code finalysis.ai.ollama.models}).</li>
 *   <li>No framework tool execution: Spring AI 2.0.1's {@code ChatModel.call} returns tool calls unexecuted on every
 *       provider (the option to turn it off is gone; {@code OllamaProvidersTest} and {@code BedrockToolCallsTest} prove
 *       it), and chat's callbacks refuse to run anyway. The chat loop runs the tools, as on Bedrock. Categorization's structured output is Ollama's JSON-schema {@code format}, through Spring AI's native
 *       structured output; the app validates every answer as before.</li>
 *   <li>No retries (Spring AI's default template retries for minutes) and no model pulls: a failed call fails, and a
 *       missing model is reported by the reachability check, never downloaded by the app.</li>
 *   <li>The path's read timeout per call; the probe's short one for {@code /api/version} and {@code /api/tags}.</li>
 *   <li>Every client, probe and request passes the {@link OllamaHostGuard}s first: none in production; the test
 *       suite's fails any host but a test's stub on loopback. A client is checked when it is built, since Spring AI's
 *       retry handling wraps whatever a request throws.</li>
 *   <li>No prompt cache: the providers report none.</li>
 * </ul>
 */
@Component
public class OllamaProviderFactory implements OllamaClients {

    static final String PROVIDER = "ollama";

    private static final ModelManagementOptions NO_PULLS = ModelManagementOptions.builder()
            .pullModelStrategy(PullModelStrategy.NEVER).build();

    private final ObjectProvider<OllamaHostGuard> guards;

    public OllamaProviderFactory(ObjectProvider<OllamaHostGuard> guards) {
        this.guards = guards;
    }

    @Override
    public ProviderSet create(URI baseUrl, OllamaProperties.Models models, int embeddingDimensions,
                              Duration readTimeout) {
        check(baseUrl); // before any client exists: a request's own check is wrapped by Spring AI's retry handling
        OllamaApi api = OllamaApi.builder()
                .baseUrl(baseUrl.toString())
                .restClientBuilder(restClient(readTimeout, readTimeout))
                .build();
        OllamaChatModel chat = OllamaChatModel.builder()
                .ollamaApi(api)
                .options(OllamaChatOptions.builder().model(models.chat()).build())
                .modelManagementOptions(NO_PULLS)
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                .build();
        OllamaEmbeddingModel embedding = OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .options(OllamaEmbeddingOptions.builder().model(models.embedding()).build())
                .modelManagementOptions(NO_PULLS)
                .build();
        return new ProviderSet(PROVIDER, chatProvider(chat, models.chat()), categorizationProvider(chat, models.chat()),
                embeddingProvider(embedding, models.embedding(), embeddingDimensions));
    }

    @Override
    public Probe probe(URI baseUrl, Duration timeout) {
        check(baseUrl);
        RestClient client = restClient(timeout, timeout).baseUrl(baseUrl.toString()).build();
        Map<?, ?> version = client.get().uri("/api/version").retrieve().body(Map.class);
        Map<?, ?> tags = client.get().uri("/api/tags").retrieve().body(Map.class);
        List<String> names = new ArrayList<>();
        if (tags != null && tags.get("models") instanceof List<?> models) {
            for (Object model : models) {
                if (model instanceof Map<?, ?> entry && entry.get("name") instanceof String name) {
                    names.add(name);
                }
            }
        }
        Object reported = version == null ? null : version.get("version");
        return new Probe(reported instanceof String text ? text : "unknown", names);
    }

    private void check(URI baseUrl) {
        guards.orderedStream().forEach(guard -> guard.check(baseUrl));
    }

    /** A client with these timeouts that runs every request past the host guards first. */
    private RestClient.Builder restClient(Duration connectTimeout, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        factory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    guards.orderedStream().forEach(guard -> guard.check(request.getURI()));
                    return execution.execute(request, body);
                });
    }

    private static ChatProvider chatProvider(ChatModel model, String modelId) {
        return new ChatProvider() {
            @Override
            public ChatModel model() {
                return model;
            }

            @Override
            public String modelId() {
                return modelId;
            }

            @Override
            public Optional<PromptCache> promptCache() {
                return Optional.empty();
            }
        };
    }

    private static CategorizationProvider categorizationProvider(ChatModel model, String modelId) {
        return new CategorizationProvider() {
            @Override
            public ChatModel model() {
                return model;
            }

            @Override
            public String modelId() {
                return modelId;
            }

            @Override
            public boolean nativeStructuredOutput() {
                return true;
            }
        };
    }

    private static EmbeddingProvider embeddingProvider(EmbeddingModel model, String modelId, int dimensions) {
        return new EmbeddingProvider() {
            @Override
            public EmbeddingModel model() {
                return model;
            }

            @Override
            public String modelId() {
                return modelId;
            }

            @Override
            public int dimensions() {
                return dimensions;
            }
        };
    }
}

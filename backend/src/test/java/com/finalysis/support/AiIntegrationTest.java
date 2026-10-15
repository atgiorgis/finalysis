package com.finalysis.support;

import static org.mockito.Mockito.mock;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link IntegrationTest} with AI on and a {@link FakeChatModel} as the only chat model (docs/plans/phase-4.md §10):
 * mode CLOUD (so the data-minimization path is the one under test), no {@code aws} profile, so no Bedrock client
 * exists and {@link NoCloudAiGuard} stays in force. The embedding model is a stub until step 8 uses it. Test classes
 * share the context and its fake: reset it ({@link FakeChatModel#reset()}) before each test.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@IntegrationTest
@Import(AiIntegrationTest.FakeModels.class)
@TestPropertySource(properties = {
        "finalysis.ai.mode=CLOUD",
        "finalysis.ai.provider=test",
        "finalysis.ai.roles.categorization=" + AiIntegrationTest.CATEGORIZATION_MODEL,
        "finalysis.ai.roles.embedding=test-embedding-model",
        "finalysis.ai.roles.main=test-main-model"})
public @interface AiIntegrationTest {

    String CATEGORIZATION_MODEL = "test-categorization-model";

    @TestConfiguration(proxyBeanMethods = false)
    class FakeModels {

        @Bean
        FakeChatModel fakeChatModel() {
            return new FakeChatModel();
        }

        @Bean
        EmbeddingModel stubEmbeddingModel() {
            return mock(EmbeddingModel.class);
        }
    }
}

package com.finalysis.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link IntegrationTest} with AI on, a {@link FakeChatModel} as the only chat model and a {@link FakeEmbeddingModel}
 * as the only embedding model (docs/plans/phase-4.md §10): mode CLOUD (so the data-minimization path is the one under
 * test), no {@code aws} profile, so no Bedrock client exists and {@link NoCloudAiGuard} stays in force. Test classes
 * share the context and its fakes: reset them ({@link FakeChatModel#reset()}, {@link FakeEmbeddingModel#reset()})
 * before each test, and delete the embeddings a test made.
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
        "finalysis.ai.roles.embedding=" + AiIntegrationTest.EMBEDDING_MODEL,
        "finalysis.ai.roles.main=test-main-model"})
public @interface AiIntegrationTest {

    String CATEGORIZATION_MODEL = "test-categorization-model";
    String EMBEDDING_MODEL = "test-embedding-model";

    @TestConfiguration(proxyBeanMethods = false)
    class FakeModels {

        @Bean
        FakeChatModel fakeChatModel() {
            return new FakeChatModel();
        }

        @Bean
        FakeEmbeddingModel fakeEmbeddingModel() {
            return new FakeEmbeddingModel();
        }
    }
}

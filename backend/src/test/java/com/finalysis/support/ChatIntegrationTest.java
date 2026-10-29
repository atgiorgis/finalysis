package com.finalysis.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link IntegrationTest} for the chat loop (docs/plans/phase-6.md §14): AI on (path CLOUD) with a
 * {@link ScriptedChatModel} as the only chat model, a {@link FakeEmbeddingModel}, and a {@link MutableClock} as the
 * {@code Clock}, starting at the sample data's "today". No {@code aws} profile, so no Bedrock client exists and
 * {@link NoCloudAiGuard} stays in force. The main model is priced like Haiku 4.5, cache prices included, so the cost
 * limits are on; with no provider cache points, chat never asks it to cache (step 8c), but a scripted call may still
 * report cached tokens to test their pricing. Test classes
 * share the context: reset the model and the clock before each test.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@IntegrationTest
@Import(ChatIntegrationTest.Beans.class)
@TestPropertySource(properties = {
        "finalysis.ai.cloud-available=true",
        "finalysis.ai.default-path=CLOUD",
        "finalysis.ai.provider=test",
        "finalysis.ai.roles.categorization=test-categorization-model",
        "finalysis.ai.roles.embedding=test-embedding-model",
        "finalysis.ai.roles.main=" + ChatIntegrationTest.MAIN_MODEL,
        "finalysis.ai.prices[" + ChatIntegrationTest.MAIN_MODEL + "].input-per-million=1.00",
        "finalysis.ai.prices[" + ChatIntegrationTest.MAIN_MODEL + "].output-per-million=5.00",
        "finalysis.ai.prices[" + ChatIntegrationTest.MAIN_MODEL + "].cache-read-per-million=0.10",
        "finalysis.ai.prices[" + ChatIntegrationTest.MAIN_MODEL + "].cache-write-per-million=1.25"})
public @interface ChatIntegrationTest {

    String MAIN_MODEL = "test-main-model";

    @TestConfiguration(proxyBeanMethods = false)
    class Beans {

        @Bean
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }

        @Bean
        FakeEmbeddingModel fakeEmbeddingModel() {
            return new FakeEmbeddingModel();
        }

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}

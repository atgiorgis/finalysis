package com.finalysis.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.categorize.AiCategorizationProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The AI settings application.yml ships after the Self-hosted measurements (docs/plans/phase-9.md, 9c-11 notes): the
 * Ollama chat model is 9a's pick, the Ollama paths ask for confidence 0.85 while Cloud keeps 0.80, and nomic's
 * search floor is 0.50 from its dev sweep.
 */
class ShippedAiSettingsTest {

    private static Binder shipped;

    @BeforeAll
    static void load() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application.yml",
                new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        shipped = Binder.get(environment);
    }

    @Test
    void theOllamaChatModelIsMinistral3_14b() {
        OllamaProperties ollama = shipped.bind("finalysis.ai.ollama", OllamaProperties.class).get();

        assertThat(ollama.models().chat()).isEqualTo("ministral-3:14b");
        assertThat(ollama.models().embedding()).isEqualTo("nomic-embed-text");
    }

    @Test
    void minConfidenceIs085OnTheOllamaPathsAnd080OnCloud() {
        AiCategorizationProperties categorization = shipped.bind("finalysis.ai.categorization",
                AiCategorizationProperties.class).get();

        assertThat(categorization.minConfidence(AiPath.CLOUD)).isEqualByComparingTo("0.80");
        assertThat(categorization.minConfidence(AiPath.SELF_HOSTED)).isEqualByComparingTo("0.85");
        assertThat(categorization.minConfidence(AiPath.LOCAL)).isEqualByComparingTo("0.85");
    }

    @Test
    void nomicsSearchFloorIs050AndTitansStays040() {
        ChatSearchProperties search = shipped.bind("finalysis.chat.search", ChatSearchProperties.class).get();

        assertThat(search.minSimilarity("nomic-embed-text")).isEqualTo(0.50);
        assertThat(search.minSimilarity("amazon.titan-embed-text-v2:0")).isEqualTo(0.40);
    }
}

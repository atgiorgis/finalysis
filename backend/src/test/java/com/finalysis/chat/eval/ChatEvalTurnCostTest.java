package com.finalysis.chat.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.chat.ChatProperties;
import com.finalysis.chat.eval.ChatEvalGuards.EvalRefusedException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** {@code -Deval.maxTurnCost=sized} (docs/plans/phase-9.md §2.2): the same worst-case rounds per turn for every model. */
class ChatEvalTurnCostTest {

    /** Phase 9 step 9a's models, by their Bedrock in-region ids (us-east-1). */
    static final List<String> OPEN_MODELS = List.of("mistral.ministral-3-8b-instruct",
            "mistral.ministral-3-14b-instruct", "google.gemma-3-12b-it", "openai.gpt-oss-20b-1:0");

    private static final ChatEvalPrices.Price SONNET = new ChatEvalPrices.Price(new BigDecimal("3.00"),
            new BigDecimal("15.00"), new BigDecimal("0.30"), new BigDecimal("3.75"));

    private static ChatEvalPrices prices(Map<String, ChatEvalPrices.Price> others) {
        Map<String, ChatEvalPrices.Price> models = new HashMap<>(others);
        models.put(ChatEvalTurnCost.REFERENCE_MODEL, SONNET);
        return new ChatEvalPrices("test", null, models);
    }

    private static ChatEvalPrices.Price uncached(String input, String output) {
        return new ChatEvalPrices.Price(new BigDecimal(input), new BigDecimal(output), null, null);
    }

    @Test
    void theAppsTwoDocumentedSettingsComeOutOfTheSizing() {
        ChatEvalPrices file = ChatEvalPrices.load();

        assertThat(ChatEvalTurnCost.sized(file, "us.anthropic.claude-sonnet-4-6")).isEqualByComparingTo("0.15");
        assertThat(ChatEvalTurnCost.sized(file, "us.anthropic.claude-haiku-4-5-20251001-v1:0"))
                .as("the aws profile's budget option").isEqualByComparingTo("0.05");
    }

    @Test
    void aRoundIsTheLargestInputAndTheWholeOutputCap() {
        assertThat(ChatEvalTurnCost.worstRound(SONNET)).as("input at the cache-write price")
                .isEqualByComparingTo("0.0675");
        assertThat(ChatEvalTurnCost.worstRound(uncached("3.00", "15.00"))).as("no cache prices: the input price")
                .isEqualByComparingTo("0.0585");
        assertThat(ChatEvalTurnCost.worstRound(uncached("0.10", "0.40"))).isEqualByComparingTo("0.0018");
    }

    @Test
    void anUncachedModelGetsTheSameRoundsRoundedUpToTheCent() {
        ChatEvalPrices prices = prices(Map.of("cheap", uncached("0.10", "0.40"), "sonnet-uncached",
                uncached("3.00", "15.00"), "odd", uncached("0.15", "0.60")));

        assertThat(ChatEvalTurnCost.sized(prices, "sonnet-uncached")).isEqualByComparingTo("0.13");
        assertThat(ChatEvalTurnCost.sized(prices, "cheap")).as("0.004 rounded up").isEqualByComparingTo("0.01");
        // 0.0027 x 0.15 / 0.0675 = 0.006: up, never down, so a model never gets fewer rounds than Sonnet
        assertThat(ChatEvalTurnCost.sized(prices, "odd")).isEqualByComparingTo("0.01");
        assertThat(ChatEvalTurnCost.sized(prices, "odd").scale()).isEqualTo(2);
    }

    @Test
    void sizingNeedsEnteredPricesForTheModelAndTheReference() {
        ChatEvalPrices awaiting = prices(Map.of("awaiting", new ChatEvalPrices.Price(null, null, null, null)));
        ChatEvalPrices noReference = new ChatEvalPrices("test", null, Map.of("cheap", uncached("0.10", "0.40")));

        assertThatThrownBy(() -> ChatEvalTurnCost.sized(awaiting, "awaiting"))
                .isInstanceOf(EvalRefusedException.class).hasMessageContaining("awaiting");
        assertThatThrownBy(() -> ChatEvalTurnCost.sized(noReference, "cheap"))
                .isInstanceOf(EvalRefusedException.class).hasMessageContaining(ChatEvalTurnCost.REFERENCE_MODEL);
    }

    /** The sizing's constants are the app's: if application.yml changes, this fails before an eval is sized wrongly. */
    @Test
    void theConstantsAreTheAppsChatSettings() throws Exception {
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load("app",
                new ClassPathResource("application.yml"));
        ChatProperties chat = new Binder(ConfigurationPropertySources.from(yaml))
                .bind("finalysis.chat", ChatProperties.class).get();

        assertThat(chat.maxInputTokensPerCall()).isEqualTo(ChatEvalTurnCost.INPUT_TOKENS_PER_CALL);
        assertThat(chat.maxOutputTokens()).isEqualTo(ChatEvalTurnCost.OUTPUT_TOKENS_PER_CALL);
        assertThat(chat.maxTurnCost()).isEqualByComparingTo(ChatEvalTurnCost.REFERENCE_LIMIT);
        assertThat(ChatEvalPrices.load().chat(ChatEvalTurnCost.REFERENCE_MODEL)).isPresent();
    }

    /** Step 9a's four models are listed, uncached, and either awaiting their prices or priced as chat models. */
    @Test
    void theFourOpenModelsAreListedWithoutCachePrices() {
        ChatEvalPrices file = ChatEvalPrices.load();

        assertThat(file.models()).containsKeys(OPEN_MODELS.toArray(String[]::new));
        for (String model : OPEN_MODELS) {
            assertThat(file.awaitingPrices(model) || file.chat(model).isPresent()).as(model).isTrue();
            assertThat(file.models().get(model).cachePriced()).as(model + " never caches").isFalse();
        }
    }
}

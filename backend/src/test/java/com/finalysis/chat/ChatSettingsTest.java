package com.finalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.ai.AiMode;
import com.finalysis.ai.AiPrices;
import com.finalysis.ai.AiProperties;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Chat's limits, prices and their startup checks (docs/plans/phase-6.md §8, §18 decision 9): the defaults, every bound,
 * the fail-closed rule for an unpriced model, the cost arithmetic, and the {@code aws} profile's prices equal to the
 * evaluation's price file.
 */
class ChatSettingsTest {

    private static ChatProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("finalysis.chat",
                ChatProperties.class);
    }

    @Test
    void theDefaultsAreThePlans() {
        ChatProperties limits = bind(Map.of());

        assertThat(limits.maxRounds()).isEqualTo(5);
        assertThat(limits.maxToolCalls()).isEqualTo(8);
        assertThat(limits.maxInputTokensPerCall()).isEqualTo(12_000);
        assertThat(limits.maxInputTokensPerTurn()).isEqualTo(40_000);
        assertThat(limits.maxOutputTokens()).isEqualTo(1_500);
        assertThat(limits.maxTurnTime()).isEqualTo(Duration.ofSeconds(45));
        assertThat(limits.maxTurnCost()).isEqualByComparingTo("0.05");
        assertThat(limits.maxDailyCost()).isEqualByComparingTo("1.00");
        assertThat(limits.retentionDays()).isEqualTo(90);
        assertThat(limits.redactTerms()).isEmpty();
        assertThat(limits.costLimited()).isTrue();
        assertThat(ChatLimits.defaults()).isEqualTo(limits);
    }

    @ParameterizedTest
    @CsvSource({
            "max-rounds, 0", "max-rounds, 21", "max-tool-calls, 0", "max-tool-calls, 51",
            "max-input-tokens-per-call, 999", "max-input-tokens-per-turn, 11999", "max-output-tokens, 99",
            "max-turn-time, 4s", "max-turn-time, 11m", "max-turn-cost, -0.01", "max-daily-cost, -1",
            "max-turn-cost, 0.0000001", "retention-days, 0", "retention-days, 3651"})
    void aBadLimitFailsStartup(String name, String value) {
        assertThatThrownBy(() -> new Binder(new MapConfigurationPropertySource(Map.of("finalysis.chat." + name, value)))
                .bind("finalysis.chat", ChatProperties.class))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("finalysis.chat." + name);
    }

    @Test
    void bothCostLimitsAtZeroAreOff() {
        assertThat(bind(Map.of("finalysis.chat.max-turn-cost", "0", "finalysis.chat.max-daily-cost", "0"))
                .costLimited()).isFalse();
    }

    private static final AiProperties CLOUD = new AiProperties(AiMode.CLOUD, "test",
            new AiProperties.Roles("main-model", "categorization-model", "embedding-model", ""), true);

    @Test
    void anUnpricedMainModelFailsStartupWhileACostLimitIsOn() {
        AiPrices none = new AiPrices(Map.of());

        assertThatThrownBy(() -> ChatStartupCheck.verify(CLOUD, none, ChatLimits.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no price for the main model main-model")
                .hasMessageContaining("max-turn-cost and finalysis.chat.max-daily-cost to 0");
        ChatStartupCheck.verify(CLOUD, none, new ChatProperties(List.of(), 5, 8, 12_000, 40_000, 1_500,
                Duration.ofSeconds(45), BigDecimal.ZERO, BigDecimal.ZERO, 90));
        ChatStartupCheck.verify(CLOUD, new AiPrices(Map.of("main-model",
                new AiPrices.Price(BigDecimal.ONE, new BigDecimal("5")))), ChatLimits.defaults());
        ChatStartupCheck.verify(new AiProperties(AiMode.OFF, null, new AiProperties.Roles(null, null, null, null),
                true), none, ChatLimits.defaults());
    }

    @Test
    void costIsTokensTimesPricesToTheMicroDollarHalfEven() {
        AiPrices.Price haiku = new AiPrices.Price(new BigDecimal("1.00"), new BigDecimal("5.00"));

        assertThat(haiku.cost(10_840, 362)).isEqualByComparingTo("0.012650");
        assertThat(haiku.cost(0, 0)).isEqualByComparingTo("0");
        // 1 token at $0.50 per million is 0.0000005: half-even to 0.000000, 3 tokens 0.0000015 to 0.000002
        AiPrices.Price half = new AiPrices.Price(new BigDecimal("0.50"), BigDecimal.ZERO);
        assertThat(half.cost(1, 0)).isEqualByComparingTo("0.000000");
        assertThat(half.cost(3, 0)).isEqualByComparingTo("0.000002");
        assertThatThrownBy(() -> new AiPrices.Price(new BigDecimal("-1"), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The cost limits and the evaluation estimate from the same numbers. */
    @Test
    void theAwsProfilesPricesEqualTheEvaluationsPriceFile() throws Exception {
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load("aws",
                new ClassPathResource("application-aws.yml"));
        AiPrices prices = new Binder(ConfigurationPropertySources.from(yaml)).bind("finalysis.ai", AiPrices.class)
                .get();

        JsonNode file;
        try (InputStream in = new ClassPathResource("eval/model-prices.json").getInputStream()) {
            file = JsonMapper.builder().build().readTree(in);
        }
        Map<String, AiPrices.Price> expected = new HashMap<>();
        file.get("models").properties().forEach(e -> expected.put(e.getKey(), new AiPrices.Price(
                new BigDecimal(e.getValue().get("inputPerMillionUsd").stringValue()),
                new BigDecimal(e.getValue().get("outputPerMillionUsd").stringValue()))));

        assertThat(prices.prices()).isNotEmpty();
        prices.prices().forEach((model, price) -> {
            assertThat(expected).as(model).containsKey(model);
            assertThat(price.inputPerMillion()).as(model).isEqualByComparingTo(expected.get(model).inputPerMillion());
            assertThat(price.outputPerMillion()).as(model)
                    .isEqualByComparingTo(expected.get(model).outputPerMillion());
        });
        assertThat(prices.of("us.anthropic.claude-haiku-4-5-20251001-v1:0")).isPresent();
    }
}

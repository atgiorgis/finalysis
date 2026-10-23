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
        assertThat(limits.maxTurnCost()).isEqualByComparingTo("0.15");
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

        assertThatThrownBy(() -> ChatStartupCheck.verify(CLOUD, none, ChatLimits.defaults(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no price for the main model main-model")
                .hasMessageContaining("max-turn-cost and finalysis.chat.max-daily-cost to 0");
        ChatStartupCheck.verify(CLOUD, none, new ChatProperties(List.of(), 5, 8, 12_000, 40_000, 1_500,
                Duration.ofSeconds(45), BigDecimal.ZERO, BigDecimal.ZERO, 90), false);
        ChatStartupCheck.verify(CLOUD, new AiPrices(Map.of("main-model",
                new AiPrices.Price(BigDecimal.ONE, new BigDecimal("5")))), ChatLimits.defaults(), true);
        ChatStartupCheck.verify(new AiProperties(AiMode.OFF, null, new AiProperties.Roles(null, null, null, null),
                true), none, ChatLimits.defaults(), false);
    }

    private static final AiPrices.Price SONNET_4_6 = new AiPrices.Price(new BigDecimal("3.00"),
            new BigDecimal("15.00"), new BigDecimal("0.30"), new BigDecimal("3.75"));
    private static final AiPrices.Price HAIKU_4_5 = new AiPrices.Price(new BigDecimal("1.00"),
            new BigDecimal("5.00"), new BigDecimal("0.10"), new BigDecimal("1.25"));

    private static AiPrices mainAt(AiPrices.Price price) {
        return new AiPrices(Map.of("main-model", price));
    }

    /**
     * Step 8d: the Sonnet 4.6 dev run stopped every turn on COST before its first call. The largest round the token
     * limit lets through (12,000 in at the cache-write price, 1,500 out) is $0.0675, above $0.05; startup now refuses.
     */
    @Test
    void aModelWhoseLargestRoundPassesTheTurnCostFailsStartup() {
        ChatProperties at005 = withTurnCost("0.05");
        assertThatThrownBy(() -> ChatStartupCheck.verify(CLOUD, mainAt(SONNET_4_6), at005, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("main-model")
                .hasMessageContaining("$0.0675")
                .hasMessageContaining("at the cache-write price")
                .hasMessageContaining("finalysis.chat.max-turn-cost $0.05")
                .hasMessageContaining("raise finalysis.chat.max-turn-cost to at least 0.0675");
        // uncached: 12,000 × $3 + 1,500 × $15 = $0.0585, still above
        assertThatThrownBy(() -> ChatStartupCheck.verify(CLOUD, mainAt(SONNET_4_6), at005, false))
                .hasMessageContaining("$0.0585");
        // Haiku 4.5: 12,000 × $1.25 + 1,500 × $5 = $0.0225
        ChatStartupCheck.verify(CLOUD, mainAt(HAIKU_4_5), at005, true);
        // the turn limit off, or raised to the round's worst case
        ChatStartupCheck.verify(CLOUD, mainAt(SONNET_4_6), withTurnCost("0"), true);
        ChatStartupCheck.verify(CLOUD, mainAt(SONNET_4_6), withTurnCost("0.0675"), true);
        assertThatThrownBy(() -> ChatStartupCheck.verify(CLOUD, mainAt(SONNET_4_6), withTurnCost("0.067499"), true))
                .isInstanceOf(IllegalStateException.class);
        // AI off: nothing to check
        ChatStartupCheck.verify(new AiProperties(AiMode.OFF, null, new AiProperties.Roles("main-model", null, null,
                null), true), mainAt(SONNET_4_6), at005, true);
    }

    // ------------------------------------------------------------------ the configurations as shipped (step 8f)

    static final String SONNET = "us.anthropic.claude-sonnet-4-6";
    static final String HAIKU = "us.anthropic.claude-haiku-4-5-20251001-v1:0";

    /** application.yml under application-aws.yml, as the aws profile runs, with overrides on top (highest first). */
    private static Binder shipped(Map<String, String> overrides) throws Exception {
        List<org.springframework.boot.context.properties.source.ConfigurationPropertySource> sources =
                new java.util.ArrayList<>();
        sources.add(new MapConfigurationPropertySource(overrides));
        List<PropertySource<?>> yaml = new java.util.ArrayList<>();
        yaml.addAll(new YamlPropertySourceLoader().load("aws", new ClassPathResource("application-aws.yml")));
        yaml.addAll(new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml")));
        ConfigurationPropertySources.from(yaml).forEach(sources::add);
        return new Binder(sources);
    }

    private static void verifyShipped(Binder binder, boolean caching) {
        ChatStartupCheck.verify(binder.bind("finalysis.ai", AiProperties.class).get(),
                binder.bind("finalysis.ai", AiPrices.class).get(),
                binder.bindOrCreate("finalysis.chat", ChatProperties.class), caching);
    }

    /** The default: chat on Sonnet 4.6 at $0.15 a turn, categorization still on Haiku 4.5; startup passes. */
    @Test
    void theDefaultSonnetConfigurationPassesStartup() throws Exception {
        Binder binder = shipped(Map.of());
        AiProperties ai = binder.bind("finalysis.ai", AiProperties.class).get();

        assertThat(ai.roles().main()).isEqualTo(SONNET);
        assertThat(ai.roles().categorization()).isEqualTo(HAIKU);
        assertThat(binder.bindOrCreate("finalysis.chat", ChatProperties.class).maxTurnCost())
                .isEqualByComparingTo("0.15");
        assertThat(binder.bindOrCreate("finalysis.chat", ChatProperties.class).maxDailyCost())
                .isEqualByComparingTo("1.00");
        assertThat(binder.bind("finalysis.ai", AiPrices.class).get().of(SONNET)).isPresent();
        verifyShipped(binder, true);
        verifyShipped(binder, false);
    }

    /** The budget option documented in application-aws.yml: Haiku 4.5 at $0.05 a turn; startup passes. */
    @Test
    void theBudgetHaikuConfigurationPassesStartup() throws Exception {
        Binder binder = shipped(Map.of("finalysis.ai.roles.main", HAIKU, "finalysis.chat.max-turn-cost", "0.05"));

        assertThat(binder.bind("finalysis.ai", AiProperties.class).get().roles().main()).isEqualTo(HAIKU);
        verifyShipped(binder, true);
        verifyShipped(binder, false);
        // and the mix the comments warn about: Sonnet at the budget limit refuses to start
        assertThatThrownBy(() -> verifyShipped(shipped(Map.of("finalysis.chat.max-turn-cost", "0.05")), true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(SONNET);
    }

    @Test
    void theShippedFilesDocumentTheBudgetOption() throws Exception {
        String aws = new String(new ClassPathResource("application-aws.yml").getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        String app = new String(new ClassPathResource("application.yml").getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(aws).contains("Budget option: Claude Haiku 4.5 (" + HAIKU + ") with max-turn-cost 0.05");
        assertThat(app).contains("0.05 with the budget").contains("Haiku 4.5 as finalysis.ai.roles.main");
    }

    /** Startup and the loop share one check: a setup startup passes lets the loop's largest first round through. */
    @Test
    void startupAndTheLoopPriceTheLargestRoundAlike() {
        for (AiPrices.Price price : List.of(SONNET_4_6, HAIKU_4_5)) {
            for (String cap : List.of("0.02", "0.05", "0.0585", "0.0675", "0.10")) {
                for (boolean caching : List.of(true, false)) {
                    ChatProperties limits = withTurnCost(cap);
                    boolean loopLetsItThrough = TurnLoop.roundFits(BigDecimal.ZERO, TurnLoop.roundWorstCase(price,
                            limits.maxInputTokensPerCall(), limits, caching), limits);
                    boolean startupPasses;
                    try {
                        ChatStartupCheck.verify(CLOUD, mainAt(price), limits, caching);
                        startupPasses = true;
                    } catch (IllegalStateException e) {
                        startupPasses = false;
                    }
                    assertThat(startupPasses).as("%s at %s, caching %s", price, cap, caching)
                            .isEqualTo(loopLetsItThrough);
                }
            }
        }
    }

    private static ChatProperties withTurnCost(String dollars) {
        return bind(Map.of("finalysis.chat.max-turn-cost", dollars));
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

    /** Step 8c: cached-prefix reads and writes at their own prices; a call's worst case before it is sent. */
    @Test
    void cachedPrefixTokensCostTheirOwnPrices() {
        AiPrices.Price haiku = new AiPrices.Price(new BigDecimal("1.00"), new BigDecimal("5.00"),
                new BigDecimal("0.10"), new BigDecimal("1.25"));

        assertThat(haiku.cachePriced()).isTrue();
        // 420 + 12×5 + 5,920×0.10 + 0×1.25 = 1,072 → $0.001072
        assertThat(haiku.cost(420, 12, 5_920, 0)).isEqualByComparingTo("0.001072");
        // the first call writes: 420 + 60 + 5,920×1.25 = 7,880 → $0.007880
        assertThat(haiku.cost(420, 12, 0, 5_920)).isEqualByComparingTo("0.007880");
        assertThat(haiku.cost(6_340, 12)).isEqualByComparingTo(haiku.cost(6_340, 12, 0, 0));
        // worst case: every estimated token at the dearer of input and write when caching, and the output cap
        assertThat(haiku.worstCase(6_000, 1_500, true)).isEqualByComparingTo("0.015000");
        assertThat(haiku.worstCase(6_000, 1_500, false)).isEqualByComparingTo("0.013500");

        // without cache prices: never asked to cache; reported cache tokens never look cheaper than input
        AiPrices.Price plain = new AiPrices.Price(new BigDecimal("1.00"), new BigDecimal("5.00"));
        assertThat(plain.cachePriced()).isFalse();
        assertThat(plain.cost(0, 0, 1_000_000, 1_000_000)).isEqualByComparingTo("3.000000");
        assertThat(plain.worstCase(6_000, 1_500, true)).isEqualByComparingTo("0.013500");

        assertThatThrownBy(() -> new AiPrices.Price(BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.10"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiPrices.Price(BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("-0.1"),
                BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
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
                new BigDecimal(e.getValue().get("outputPerMillionUsd").stringValue()),
                e.getValue().has("cacheReadPerMillionUsd")
                        ? new BigDecimal(e.getValue().get("cacheReadPerMillionUsd").stringValue()) : null,
                e.getValue().has("cacheWritePerMillionUsd")
                        ? new BigDecimal(e.getValue().get("cacheWritePerMillionUsd").stringValue()) : null)));

        assertThat(prices.prices()).isNotEmpty();
        prices.prices().forEach((model, price) -> {
            assertThat(expected).as(model).containsKey(model);
            assertThat(price.inputPerMillion()).as(model).isEqualByComparingTo(expected.get(model).inputPerMillion());
            assertThat(price.outputPerMillion()).as(model)
                    .isEqualByComparingTo(expected.get(model).outputPerMillion());
            assertThat(price.cachePriced()).as(model + " cache prices").isEqualTo(expected.get(model).cachePriced());
            if (price.cachePriced()) {
                assertThat(price.cacheReadPerMillion()).as(model)
                        .isEqualByComparingTo(expected.get(model).cacheReadPerMillion());
                assertThat(price.cacheWritePerMillion()).as(model)
                        .isEqualByComparingTo(expected.get(model).cacheWritePerMillion());
            }
        });
        assertThat(prices.of("us.anthropic.claude-haiku-4-5-20251001-v1:0")).hasValueSatisfying(
                haiku -> assertThat(haiku.cachePriced()).isTrue());
    }
}

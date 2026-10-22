package com.finalysis.chat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.chat.ChatModelClient;
import com.finalysis.chat.ChatService;
import com.finalysis.chat.ChatTurnResponse;
import com.finalysis.chat.eval.ChatCallRecorder.CallUsage;
import com.finalysis.support.IntegrationTest;
import com.finalysis.support.MutableClock;
import com.finalysis.support.NoCloudAiGuard;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Opt-in live check that prompt caching is active for a model (docs/plans/phase-6.md step 8c): one synthetic question
 * on the analytics fixture through a whole turn, as {@link LiveChatSmokeTest}, against {@code -Deval.model} (required,
 * priced from the price file with its cache prices). The turn's first call writes the fixed prefix (tools, system
 * prompt, context block) to the cache, or reads it when a run less than five minutes earlier wrote it; its second call
 * must read it: cached tokens above 0. Prints one line per call, counts only. Tagged {@value NoCloudAiGuard#LIVE_TAG},
 * never part of {@code ./mvnw verify}:
 * <pre>cd backend &amp;&amp; ./mvnw test -Pai-smoke -Dtest=LivePromptCacheSmokeTest -Deval.model=&lt;id&gt;</pre>
 */
@Tag(NoCloudAiGuard.LIVE_TAG)
@EnabledIfSystemProperty(named = NoCloudAiGuard.LIVE_PROPERTY, matches = "true")
@IntegrationTest
@AutoConfigureMockMvc
@ActiveProfiles("aws")
@Import({ChatEvalBeans.class, ChatEvalClock.class})
class LivePromptCacheSmokeTest {

    private final ChatService chat;
    private final ChatModelClient client;
    private final ChatCallRecorder recorder;
    private final ChatEvalSeeder seeder;
    private final MutableClock clock;

    LivePromptCacheSmokeTest(ChatService chat, ChatModelClient client, ChatCallRecorder recorder,
                             ChatEvalSeeder seeder, MutableClock clock) {
        this.chat = chat;
        this.client = client;
        this.recorder = recorder;
        this.seeder = seeder;
        this.clock = clock;
    }

    /** {@code -Deval.model}, required here, priced from the price file like the evaluation, cache prices included. */
    @DynamicPropertySource
    static void model(DynamicPropertyRegistry registry) {
        ChatEvalPrices prices = ChatEvalPrices.load();
        String model = ChatEvalGuards.model(System.getProperty("eval.model", ""), prices);
        registry.add("finalysis.ai.roles.main", () -> model);
        prices.register(registry, model);
    }

    @Test
    void theSecondCallOfATurnReadsTheCachedPrefix() throws Exception {
        assertThat(client.promptCaching()).as("caching for " + client.modelId() + " (OFF_NO_CACHE_PRICES: add "
                + "cacheReadPerMillionUsd and cacheWritePerMillionUsd to eval/model-prices.json)")
                .isEqualTo(ChatModelClient.PromptCaching.ON);
        seeder.seed("FIXTURE");
        clock.set(LocalDateTime.of(2026, 4, 15, 12, 0));
        try {
            recorder.begin();
            ChatTurnResponse turn = chat.ask(chat.create().id(), LiveChatSmokeTest.QUESTION);
            List<CallUsage> calls = recorder.end().usages();

            for (int i = 0; i < calls.size(); i++) {
                System.out.printf("cache-smoke model=%s call=%d inputTokens=%s cacheReadTokens=%s "
                                + "cacheWriteTokens=%s%n", client.modelId(), i + 1, calls.get(i).inputTokens(),
                        calls.get(i).cacheReadTokens(), calls.get(i).cacheWriteTokens());
            }
            System.out.printf("cache-smoke model=%s caching=%s turn cacheReadTokens=%s cacheWriteTokens=%s cost=%s%n",
                    client.modelId(), client.promptCaching(), turn.usage().cacheReadTokens(),
                    turn.usage().cacheWriteTokens(), turn.usage().estimatedCost());

            assertThat(calls).as("a tool round and an answer: two calls at least").hasSizeGreaterThanOrEqualTo(2);
            assertThat(cached(calls.get(0))).as("the first call wrote (or read) the prefix").isPositive();
            assertThat(Objects.requireNonNullElse(calls.get(1).cacheReadTokens(), 0L))
                    .as("the second call read the cached prefix").isPositive();
            assertThat(client.promptCaching()).as("the provider accepted the cache points")
                    .isEqualTo(ChatModelClient.PromptCaching.ON);
        } finally {
            seeder.clear();
        }
    }

    private static long cached(CallUsage call) {
        return Objects.requireNonNullElse(call.cacheReadTokens(), 0L)
                + Objects.requireNonNullElse(call.cacheWriteTokens(), 0L);
    }
}

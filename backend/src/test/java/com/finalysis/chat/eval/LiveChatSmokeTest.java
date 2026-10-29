package com.finalysis.chat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.chat.AnswerSource;
import com.finalysis.chat.ChatService;
import com.finalysis.chat.ChatTurnResponse;
import com.finalysis.chat.ToolStatus;
import com.finalysis.support.IntegrationTest;
import com.finalysis.support.MutableClock;
import com.finalysis.support.NoCloudAiGuard;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Opt-in live smoke test of chat (docs/plans/phase-6.md §14, step 8): one synthetic question on the analytics fixture
 * (synthetic, two accounts) through the whole turn, against the Bedrock model {@code -Deval.model} (default: the
 * {@code aws} profile's main role). It proves, before a dev run, that the model's Converse path works with tools: a
 * tool round, its results sent back with the model's ids and any reasoning content unchanged, and a final answer. It
 * prints one line of counts and never the answer. Never part of {@code ./mvnw verify}: tagged
 * {@value NoCloudAiGuard#LIVE_TAG} and switched by {@code -D}{@value NoCloudAiGuard#LIVE_PROPERTY}{@code =true}, which
 * the {@code ai-smoke} profile sets:
 * <pre>cd backend &amp;&amp; ./mvnw test -Pai-smoke -Dtest=LiveChatSmokeTest -Deval.model=&lt;id&gt;</pre>
 */
@Tag(NoCloudAiGuard.LIVE_TAG)
@EnabledIfSystemProperty(named = NoCloudAiGuard.LIVE_PROPERTY, matches = "true")
@IntegrationTest
@AutoConfigureMockMvc
@ActiveProfiles("aws")
@TestPropertySource(properties = "finalysis.ai.default-path=CLOUD") // a fresh test vault on Cloud (AI-03)
@Import({ChatEvalBeans.class, ChatEvalClock.class})
class LiveChatSmokeTest {

    static final String QUESTION = "How much did I spend in total in February 2026?";

    private final ChatService chat;
    private final ChatEvalSeeder seeder;
    private final MutableClock clock;

    LiveChatSmokeTest(ChatService chat, ChatEvalSeeder seeder, MutableClock clock) {
        this.chat = chat;
        this.seeder = seeder;
        this.clock = clock;
    }

    /** {@code -Deval.model}, if given, priced from the price file like the evaluation; else the profile's main role. */
    @DynamicPropertySource
    static void model(DynamicPropertyRegistry registry) {
        String model = System.getProperty("eval.model", "");
        if (model.isBlank()) {
            return;
        }
        ChatEvalPrices prices = ChatEvalPrices.load();
        ChatEvalGuards.model(model, prices);
        registry.add("finalysis.ai.roles.main", () -> model);
        prices.register(registry, model);
    }

    @Test
    void oneSyntheticQuestionIsAnsweredWithATool() throws Exception {
        seeder.seed("FIXTURE");
        clock.set(LocalDateTime.of(2026, 4, 15, 12, 0));
        try {
            ChatTurnResponse turn = chat.ask(chat.create().id(), QUESTION);

            System.out.printf("chat-smoke model=%s source=%s grounding=%s retried=%s rounds=%d tools=[%s] limit=%s "
                            + "inputTokens=%s cacheReadTokens=%s cacheWriteTokens=%s outputTokens=%s cost=%s%n",
                    turn.usage().model(), turn.answerSource(),
                    turn.grounding().status(), turn.grounding().retried(), turn.usage().rounds(),
                    turn.tools().stream().map(t -> t.name() + ":" + t.status()).collect(Collectors.joining(",")),
                    turn.limitHit(), turn.usage().inputTokens(), turn.usage().cacheReadTokens(),
                    turn.usage().cacheWriteTokens(), turn.usage().outputTokens(),
                    turn.usage().estimatedCost());
            assertThat(turn.answerSource()).isIn(AnswerSource.MODEL, AnswerSource.FALLBACK);
            assertThat(turn.tools()).as("at least one tool ran").anyMatch(t -> t.status() == ToolStatus.OK);
            assertThat(turn.usage().rounds()).as("a tool round and an answer").isGreaterThanOrEqualTo(2);
        } finally {
            seeder.clear();
        }
    }
}

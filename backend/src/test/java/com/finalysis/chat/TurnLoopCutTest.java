package com.finalysis.chat;

import static com.finalysis.support.ScriptedChatModel.call;
import static com.finalysis.support.ScriptedChatModel.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.ai.AiPrices;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.chat.grounding.AnswerGrounding;
import com.finalysis.chat.outbound.ChatPayloadGuard;
import com.finalysis.chat.outbound.ContextBlockFactory;
import com.finalysis.chat.outbound.OutboundTurn;
import com.finalysis.chat.outbound.OutboundTurnFactory;
import com.finalysis.chat.tool.ToolProblem;
import com.finalysis.chat.tool.ToolRegistry;
import com.finalysis.support.ChatIntegrationTest;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.MutableClock;
import com.finalysis.support.ScriptedChatModel;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The per-call input cap (docs/plans/phase-6.md §8, AI-05): before a round, the turn's oldest tool results are cut from
 * the prompt to fit, and only when nothing is left to cut does the turn stop with {@code TOKENS}. A cut result still
 * grounds figures: the model saw it in an earlier round. The cap here is set just above the turn's fixed prompt, so one
 * result can't fit.
 */
@ChatIntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TurnLoopCutTest {

    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final ChatModelClient client;
    private final ToolExecutor executor;
    private final AnswerGrounding grounding;
    private final AiPrices prices;
    private final MutableClock clock;
    private final ToolRegistry registry;
    private final OutboundTurnFactory turns;
    private final ContextBlockFactory context;
    private final ChatPayloadGuard guard;
    private final ChatPrompt prompt;
    private final ScriptedChatModel model;

    TurnLoopCutTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper json,
                    ChatModelClient client, ToolExecutor executor, AnswerGrounding grounding, AiPrices prices,
                    MutableClock clock, ToolRegistry registry, OutboundTurnFactory turns, ContextBlockFactory context,
                    ChatPayloadGuard guard, ChatPrompt prompt, ScriptedChatModel model) {
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, json);
        this.client = client;
        this.executor = executor;
        this.grounding = grounding;
        this.prices = prices;
        this.clock = clock;
        this.registry = registry;
        this.turns = turns;
        this.context = context;
        this.guard = guard;
        this.prompt = prompt;
        this.model = model;
    }

    @BeforeAll
    void importFixture() throws Exception {
        fixture.load();
    }

    @AfterAll
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    @BeforeEach
    void reset() {
        model.reset();
        clock.reset();
    }

    private record Start(OutboundTurn turn, List<Message> messages, int estimate) {
    }

    private Start start() {
        OutboundTurn turn = turns.start();
        List<Message> messages = prompt.start(guard.check(context.build(turn, LocalDate.of(2026, 10, 15)), turn), ChatHistory.NONE,
                guard.checkText("How much did I spend from January to April?"));
        int definitions = registry.definitions().stream()
                .mapToInt(d -> d.name().length() + d.description().length() + d.inputSchema().length()).sum();
        return new Start(turn, messages, TokenEstimate.of(messages, definitions));
    }

    private TurnLoop loop(int maxInputTokensPerCall) {
        return new TurnLoop(client, executor, grounding, new ChatProperties(List.of(), 5, 8, maxInputTokensPerCall,
                100_000, 1_500, Duration.ofSeconds(45), new BigDecimal("0.05"), new BigDecimal("1.00"), 90), prices,
                clock,
                registry);
    }

    @Test
    void theOldestResultIsCutToFitAndStillGroundsTheAnswer() {
        Start start = start();
        model.then(model.toolCalls(call("get_month_totals", "{\"from\": \"2026-01\", \"to\": \"2026-04\"}")),
                text("You spent $1,809.48 in March 2026."));

        TurnLoop.Result result = loop(start.estimate() + 400).run(start.turn(), start.messages(), 0,
                "How much did I spend from January to April?", List.of());

        assertThat(result.source()).isEqualTo(AnswerSource.MODEL);
        assertThat(result.limit()).isNull();
        ToolResponseMessage sent = (ToolResponseMessage) model.prompts().get(1).getInstructions().getLast();
        assertThat(sent.getResponses()).singleElement().satisfies(r ->
                assertThat(r.responseData()).contains("\"error\":\"CUT\"").contains(ToolProblem.CUT.text())
                        .doesNotContain("1809.48"));
        assertThat(result.evidence().results()).singleElement()
                .satisfies(r -> assertThat(r.outcome().json()).contains("1809.48"));
    }

    @Test
    void whenNothingIsLeftToCutTheTurnStopsWithTokens() {
        Start start = start();
        model.then(model.toolCalls(call("get_month_totals", "{\"from\": \"2026-01\", \"to\": \"2026-04\"}")));

        TurnLoop.Result result = loop(start.estimate() + 10).run(start.turn(), start.messages(), 0,
                "How much did I spend from January to April?", List.of());

        assertThat(result.limit()).isEqualTo(LimitHit.TOKENS);
        assertThat(result.source()).isEqualTo(AnswerSource.FALLBACK);
        assertThat(result.fallback()).contains("1,809.48");
        assertThat(result.rounds()).isEqualTo(1);
    }
}

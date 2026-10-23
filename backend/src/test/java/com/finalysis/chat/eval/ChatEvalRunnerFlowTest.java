package com.finalysis.chat.eval;

import static com.finalysis.support.ScriptedChatModel.call;
import static com.finalysis.support.ScriptedChatModel.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.chat.AnswerSource;
import com.finalysis.chat.eval.ChatEvalResults.Observation;
import com.finalysis.chat.eval.ChatEvalResults.QuestionResult;
import com.finalysis.chat.eval.ChatEvalResults.Run;
import com.finalysis.chat.eval.ChatEvalSet.Dataset;
import com.finalysis.chat.eval.ChatEvalSet.Expect;
import com.finalysis.chat.eval.ChatEvalSet.ExpectedFigure;
import com.finalysis.chat.eval.ChatEvalSet.Question;
import com.finalysis.chat.eval.ChatEvalSet.Turn;
import com.finalysis.support.ChatIntegrationTest;
import com.finalysis.support.ScriptedChatModel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The evaluation runner end to end with a scripted model (step 8; no cloud call, {@code NoCloudAiGuard} in force): the
 * sample data seeded with the answer key, the search index built (fake embeddings), each question in its own
 * conversation through {@code ChatService}, the recorder seeing the grounding retry, each turn scored, and the
 * database left empty.
 */
@ChatIntegrationTest
@AutoConfigureMockMvc
@Import(ChatEvalBeans.class)
class ChatEvalRunnerFlowTest {

    private static final Dataset SAMPLE = new Dataset("SAMPLE", LocalDate.of(2026, 10, 15));

    private final ChatEvalRunner runner;
    private final ScriptedChatModel model;
    private final JdbcTemplate jdbc;

    ChatEvalRunnerFlowTest(ChatEvalRunner runner, ChatModel chatModel, JdbcTemplate jdbc) {
        this.runner = runner;
        this.model = (ScriptedChatModel) ((ChatCallRecorder.Recording) chatModel).delegate();
        this.jdbc = jdbc;
    }

    private static Turn turn(String ask, Expect expect, String tool, String... figures) {
        return new Turn(ask, expect, List.of(tool == null ? Set.of() : Set.of(tool)),
                figures.length == 0 ? List.of() : List.of(List.of(figures).stream().map(ExpectedFigure::parse).toList()),
                List.of(), List.of(), null, false);
    }

    @Test
    void aSetRunsThroughTheChatPathAndIsScored() throws Exception {
        ChatEvalSet set = new ChatEvalSet("dev", 1, "sha", Map.of("SAMPLE", SAMPLE), List.of(), List.of(
                new Question("t-l01", "assistant", "LOOKUP", "SAMPLE", List.of(), List.of(
                        turn("What did I spend on dining in June 2026?", Expect.ANSWER, "get_category_totals",
                                "190.10"))),
                new Question("t-l02", "assistant", "LOOKUP", "SAMPLE", List.of(), List.of(
                        turn("How much did I spend in total in June?", Expect.ANSWER, "get_month_totals",
                                "3638.53"))),
                new Question("t-x01", "assistant", "CANT_ANSWER", "SAMPLE", List.of(), List.of(
                        turn("What's my credit score?", Expect.REFUSAL, null)))));
        model.reset();
        model.then(model.toolCalls(call("get_category_totals",
                        "{\"from\": \"2026-06\", \"to\": \"2026-06\", \"category\": \"Dining\"}")),
                text("You spent $190.10 on dining in June 2026."),
                model.toolCalls(call("get_month_totals", "{\"from\": \"2026-06\", \"to\": \"2026-06\"}")),
                text("So far in 2026 you spent $3,638.53."),
                text("You spent $3,638.53 in June."),
                text("I can't tell your credit score from your data."));

        Run run = runner.run(set, ChatIntegrationTest.MAIN_MODEL, ChatEvalPrices.load(), new BigDecimal("5.00"), false);

        assertThat(model.remaining()).isZero();
        assertThat(run.index()).singleElement().satisfies(index -> {
            assertThat(index.status()).isEqualTo("DONE");
            assertThat(index.merchants()).isPositive();
        });
        assertThat(run.todayByDataset()).containsEntry("SAMPLE", "2026-10-15");
        assertThat(run.questions()).extracting(QuestionResult::id).containsExactly("t-l01", "t-l02", "t-x01");
        assertThat(run.questions()).allMatch(QuestionResult::correct);

        Observation first = run.questions().getFirst().turns().getFirst().observed();
        assertThat(first.source()).isEqualTo(AnswerSource.MODEL);
        assertThat(first.retried()).isFalse();
        assertThat(first.tools()).extracting(ChatEvalResults.ToolUse::name).containsExactly("get_category_totals");
        assertThat(first.inputTokens()).isPositive();
        assertThat(first.cost()).isPositive();

        Observation retried = run.questions().get(1).turns().getFirst().observed();
        assertThat(retried.retried()).isTrue();
        assertThat(retried.retryFigures()).containsExactly("2026");
        assertThat(retried.rejectedAnswer()).isEqualTo("So far in 2026 you spent $3,638.53.");

        assertThat(run.questions().getLast().turns().getFirst().score().refusal()).isTrue();
        assertThat(run.ledgerCost()).isPositive();
        assertThat(run.settings()).containsKey("finalysis.chat.max-rounds")
                .containsEntry("finalysis.ai.roles.main", ChatIntegrationTest.MAIN_MODEL);
        assertThat(run.promptVersion()).isEqualTo("chat-8");

        ChatEvalMetrics metrics = ChatEvalMetrics.of(run.questions(), List.of("LOOKUP", "CANT_ANSWER"));
        assertThat(metrics.grounding().bareYearRetries()).isEqualTo(1);
        assertThat(metrics.all().toolChoice()).isEqualTo(3);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM txn", Integer.class)).as("left empty").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_conversation", Integer.class)).isZero();
    }
}

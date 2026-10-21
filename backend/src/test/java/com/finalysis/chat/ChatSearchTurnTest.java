package com.finalysis.chat;

import static com.finalysis.support.ScriptedChatModel.call;
import static com.finalysis.support.ScriptedChatModel.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.analytics.AnalyticsFixture;
import com.finalysis.analytics.MerchantTotalsResponse;
import com.finalysis.analytics.MerchantTotalsService;
import com.finalysis.categorize.MerchantIndexRefresh;
import com.finalysis.chat.citation.Citation;
import com.finalysis.chat.grounding.GroundingStatus;
import com.finalysis.chat.tool.FigureBasis;
import com.finalysis.support.ChatIntegrationTest;
import com.finalysis.support.FakeEmbeddingModel;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.MutableClock;
import com.finalysis.support.ScriptedChatModel;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Routing (CHT-03; docs/plans/phase-6.md §4.4) through whole turns with a scripted model on the analytics fixture: a fuzzy
 * question goes to {@code search_transactions}, then a numeric tool with the candidates the model picked, and the answer
 * is grounded on the numeric tool's Java-computed figure and cited down to the drill-downs. The search's neighbours are
 * chosen with the fake embedding model, as in {@code MerchantSearchTest}. No model is called.
 */
@ChatIntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatSearchTurnTest {

    static final String SEARCH = "{\"query\": \"restaurant\", \"from\": \"2026-01\", \"to\": \"2026-04\", \"limit\": 5}";
    static final String PICKED = "{\"merchants\": [\"FIXTURE DINER\", \"FIXTURE GROCER\"], \"from\": \"2026-01\", "
            + "\"to\": \"2026-04\"}";

    private final ImportTestSupport support;
    private final AnalyticsFixture fixture;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ChatService chat;
    private final ScriptedChatModel model;
    private final MutableClock clock;
    private final FakeEmbeddingModel embeddingModel;
    private final MerchantIndexRefresh refresh;
    private final MerchantTotalsService merchantTotals;
    private long conversation;

    ChatSearchTurnTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper json,
                       ChatService chat, ScriptedChatModel model, MutableClock clock,
                       FakeEmbeddingModel embeddingModel, MerchantIndexRefresh refresh,
                       MerchantTotalsService merchantTotals) {
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.fixture = new AnalyticsFixture(support, jdbc, json);
        this.jdbc = jdbc;
        this.json = json;
        this.chat = chat;
        this.model = model;
        this.clock = clock;
        this.embeddingModel = embeddingModel;
        this.refresh = refresh;
        this.merchantTotals = merchantTotals;
    }

    @BeforeAll
    void importAndIndex() throws Exception {
        fixture.load();
        jdbc.update("DELETE FROM merchant_embedding");
        embeddingModel.reset()
                .near("FIXTURE DINER", "RESTAURANT", 0.82)
                .near("FIXTURE GROCER", "RESTAURANT", 0.55);
        refresh.refresh();
    }

    @AfterAll
    void cleanUp() {
        jdbc.update("DELETE FROM merchant_embedding");
        embeddingModel.reset();
        support.deleteCreatedAccounts();
    }

    @BeforeEach
    void newConversation() {
        model.reset();
        clock.reset();
        conversation = chat.create().id();
    }

    @AfterEach
    void deleteConversations() {
        jdbc.update("DELETE FROM chat_conversation");
        assertThat(model.remaining()).as("every scripted step was used").isZero();
    }

    /** The JSON results of the prompt's last tool-response message. */
    private List<JsonNode> lastResults(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        ToolResponseMessage last = (ToolResponseMessage) messages.stream()
                .filter(m -> m instanceof ToolResponseMessage).reduce((a, b) -> b).orElseThrow();
        return last.getResponses().stream().map(r -> json.readTree(r.responseData())).toList();
    }

    private BigDecimal spent(String merchant) {
        return merchantTotals.merchants(Optional.of(YearMonth.of(2026, 1)), Optional.of(YearMonth.of(2026, 4)),
                        Optional.empty(), Optional.empty(), 50).items().stream()
                .filter(m -> merchant.equals(m.merchant())).map(MerchantTotalsResponse.MerchantTotal::spent)
                .findFirst().orElseThrow();
    }

    @Test
    void searchThenTheNumericToolGivesAGroundedAndCitedAnswer() {
        BigDecimal both = spent("FIXTURE DINER").add(spent("FIXTURE GROCER"));
        model.then(model.toolCalls(call("search_transactions", SEARCH)),
                model.toolCalls(call("get_merchant_totals", PICKED)),
                text(prompt -> {
                    JsonNode combined = lastResults(prompt).getFirst().get("result").get("combined");
                    return "You spent $" + combined.get("spent").stringValue() + " at those two places from January "
                            + "to April 2026 [" + combined.get("ref").stringValue() + "].";
                }));

        ChatTurnResponse response = chat.ask(conversation, "How much do I spend at restaurants?");

        assertThat(response.answerSource()).isEqualTo(AnswerSource.MODEL);
        assertThat(response.answer()).isEqualTo("You spent $" + both.toPlainString()
                + " at those two places from January to April 2026 [1].");
        assertThat(response.grounding().status()).isEqualTo(GroundingStatus.GROUNDED);
        assertThat(response.grounding().retried()).isFalse();
        assertThat(response.tools()).extracting(ChatTurnResponse.ToolUse::name)
                .containsExactly("search_transactions", "get_merchant_totals");
        assertThat(response.tools()).allSatisfy(tool -> assertThat(tool.status()).isEqualTo(ToolStatus.OK));

        // the citation of the combined figure: its two stored merchants' drill-downs, which add up to it
        assertThat(response.citations()).singleElement().isInstanceOfSatisfying(Citation.Total.class, total -> {
            assertThat(total.marker()).isEqualTo(1);
            Citation.CitedFigure figure = total.figures().getFirst();
            assertThat(figure.field()).isEqualTo("spent");
            assertThat(figure.relation()).isEqualTo(FigureBasis.Relation.SUM);
            assertThat(figure.terms()).extracting(t -> t.drillDown().params().get("merchant"))
                    .containsExactly("FIXTURE DINER", "FIXTURE GROCER");
            BigDecimal sum = figure.terms().stream()
                    .map(t -> t.drillDown().total().net().multiply(BigDecimal.valueOf(t.sign())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).isEqualByComparingTo(both);
            assertThat(figure.terms()).allSatisfy(t -> assertThat(t.drillDown().sampleTxns()).isNotEmpty());
        });

        // the search's text went to the embedding model as checked, and the model saw the candidates
        assertThat(embeddingModel.inputs()).contains("RESTAURANT");
        JsonNode candidates = lastResults(model.prompts().get(1)).getFirst().get("result").get("candidates");
        assertThat(candidates.valueStream().map(c -> c.get("merchant").stringValue()))
                .containsExactly("FIXTURE DINER", "FIXTURE GROCER");
        assertThat(model.prompts().getFirst().getInstructions().getFirst().getText())
                .contains("first search_transactions").contains("get_merchant_totals");
        assertThat(jdbc.queryForObject("SELECT prompt_version FROM chat_turn WHERE conversation_id = ?", String.class,
                conversation)).isEqualTo("chat-2");
    }

    /** The model adding the candidates' figures itself fails grounding (§4.4): the retry sends it to the tool. */
    @Test
    void aSumWrittenByTheModelIsRetriedThenGroundedOnTheToolsCombinedFigure() {
        BigDecimal both = spent("FIXTURE DINER").add(spent("FIXTURE GROCER"));
        model.then(model.toolCalls(call("search_transactions", SEARCH)),
                text("Together that is $" + both.add(new BigDecimal("0.01")).toPlainString() + "."),
                model.toolCalls(call("get_merchant_totals", PICKED)),
                text(prompt -> "Together that is $" + lastResults(prompt).getFirst().get("result").get("combined")
                        .get("spent").stringValue() + "."));

        ChatTurnResponse response = chat.ask(conversation, "How much do I spend at restaurants?");

        assertThat(response.grounding().status()).isEqualTo(GroundingStatus.GROUNDED);
        assertThat(response.grounding().retried()).isTrue();
        assertThat(response.answer()).startsWith("Together that is $" + both.toPlainString());
        assertThat(response.tools()).extracting(ChatTurnResponse.ToolUse::name)
                .containsExactly("search_transactions", "get_merchant_totals");
    }

    /** A similarity is a label of the result, never a figure: quoting it is ungrounded, and the retry drops it. */
    @Test
    void aQuotedSimilarityIsNotGrounded() {
        model.then(model.toolCalls(call("search_transactions", SEARCH)),
                text("FIXTURE DINER is the closest match, at 0.82."),
                text("FIXTURE DINER is the closest match."));

        ChatTurnResponse response = chat.ask(conversation, "Which restaurant do I go to?");

        assertThat(response.grounding().retried()).isTrue();
        assertThat(response.grounding().status()).isEqualTo(GroundingStatus.NO_FIGURES);
        assertThat(response.answer()).isEqualTo("FIXTURE DINER is the closest match.");
    }

    /** A search that finds nothing fitting ends in an answer without figures (the prompt's rule), and it shows. */
    @Test
    void aSearchWithNoFittingCandidateIsAnsweredWithoutFigures() {
        model.then(model.toolCalls(call("search_transactions", "{\"query\": \"ski resort\", \"limit\": 5}")),
                text("I couldn't find that merchant in your data."));

        ChatTurnResponse response = chat.ask(conversation, "How much did I spend at the ski resort?");

        assertThat(response.grounding().status()).isEqualTo(GroundingStatus.NO_FIGURES);
        JsonNode result = lastResults(model.prompts().getLast()).getFirst().get("result");
        assertThat(result.get("candidates")).isEmpty();
        assertThat(result.get("byMeaning").booleanValue()).isTrue();
    }
}

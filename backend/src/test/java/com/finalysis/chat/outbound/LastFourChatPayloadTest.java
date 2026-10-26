package com.finalysis.chat.outbound;

import static com.finalysis.support.ScriptedChatModel.call;
import static com.finalysis.support.ScriptedChatModel.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.support.ChatIntegrationTest;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.MutableClock;
import com.finalysis.support.ScriptedChatModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 7 step 7d, chat's side of {@code com.finalysis.categorize.LastFourOutboundTest}: accounts created with
 * {@code POST /api/accounts} as the Import page sends them, a sample statement imported through the page's multipart
 * request, synthetic rows carrying each last four, then a chat turn whose question names a last four and whose tools
 * read every account and row. No prompt (question, tool arguments, tool results) carries a last four, an account name
 * or the institution.
 */
@ChatIntegrationTest
@AutoConfigureMockMvc
class LastFourChatPayloadTest {

    private static final String CARD_LAST_FOUR = "2001";
    private static final String SAVINGS_LAST_FOUR = "7364";
    private static final String CARD_NAME = "Lastfour Rewards Card";
    private static final String SAVINGS_NAME = "Lastfour Rainy Day";
    private static final String INSTITUTION = "Quorvale Federal";

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ScriptedChatModel model;
    private final MutableClock clock;
    private final ImportTestSupport support;
    private final List<Long> accounts = new ArrayList<>();

    LastFourChatPayloadTest(MockMvc mvc, JdbcTemplate jdbc, JsonMapper json, ScriptedChatModel model, MutableClock clock,
                            AccountRepository accountRepository) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.json = json;
        this.model = model;
        this.clock = clock;
        this.support = new ImportTestSupport(mvc, accountRepository, jdbc);
    }

    @BeforeEach
    void reset() {
        model.reset();
        clock.reset();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_usage");
        for (long id : accounts) {
            jdbc.update("DELETE FROM txn WHERE account_id = ?", id);
            jdbc.update("DELETE FROM statement WHERE account_id = ?", id);
            jdbc.update("DELETE FROM account WHERE id = ?", id);
        }
    }

    private long createAccount(String name, String type, String lastFour) throws Exception {
        String body = json.writeValueAsString(Map.of("name", name, "institution", INSTITUTION, "type", type,
                "lastFour", lastFour));
        long id = json.readTree(mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        accounts.add(id);
        return id;
    }

    /** Everything a prompt sends but the model's own text, as in {@code ChatTurnPayloadTest}. */
    private static List<String> sent(Prompt prompt) {
        List<String> sent = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            switch (message) {
                case ToolResponseMessage tools -> tools.getResponses().forEach(r -> sent.add(r.responseData()));
                case AssistantMessage assistant -> assistant.getToolCalls().forEach(c -> sent.add(c.arguments()));
                default -> sent.add(message.getText());
            }
        }
        return sent;
    }

    @Test
    void noAccountsLastFourReachesAnyChatPayload() throws Exception {
        long card = createAccount(CARD_NAME, "CREDIT_CARD", CARD_LAST_FOUR);
        createAccount(SAVINGS_NAME, "SAVINGS", SAVINGS_LAST_FOUR);
        long statementId = json.readTree(support.upload(card, "apex-visa_2026-08.qfx",
                        ImportTestSupport.sample("ofx/apex-visa_2026-08.qfx"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("statementId").asLong();
        for (String description : List.of("QUILLFEATHER EMPORIUM CARD ENDING " + CARD_LAST_FOUR,
                "ZORVEX KIOSK " + SAVINGS_LAST_FOUR)) {
            jdbc.update("""
                    INSERT INTO txn (account_id, statement_id, txn_date, raw_description, merchant, amount,
                                     source_section, fingerprint)
                    VALUES (?, ?, DATE '2026-08-20', ?, ?, -9.99, 'WITHDRAWAL', ?)""",
                    card, statementId, description, description, "fp-" + UUID.randomUUID());
        }
        long conversation = json.readTree(mvc.perform(post("/api/chat/conversations")).andReturn().getResponse()
                .getContentAsString()).get("id").asLong();
        model.then(model.toolCalls(
                        call("get_account_totals", "{\"from\": \"2026-08\", \"to\": \"2026-08\"}"),
                        call("list_transactions", "{\"month\": \"2026-08\", \"limit\": 50}"),
                        call("get_top_merchants", "{\"from\": \"2026-08\", \"to\": \"2026-08\", \"limit\": 20}"),
                        call("get_data_coverage", "{}")),
                text("Here is what your statements show."));

        mvc.perform(post("/api/chat/conversations/{id}/turns", conversation).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("message", "What did I spend on my " + CARD_NAME
                                + " ending " + CARD_LAST_FOUR + " and the " + SAVINGS_NAME + " ending "
                                + SAVINGS_LAST_FOUR + " at " + INSTITUTION + " in August 2026?"))))
                .andExpect(status().isOk());

        List<Prompt> prompts = model.prompts();
        assertThat(prompts).hasSize(2);
        String all = String.join("\n", prompts.stream().flatMap(p -> sent(p).stream()).toList());
        assertThat(all).as("the tools read the card").contains("CARD-1").contains("QUILLFEATHER");
        assertThat(all).doesNotContain(CARD_LAST_FOUR).doesNotContain(SAVINGS_LAST_FOUR)
                .doesNotContain("4999000011112001")
                .doesNotContainIgnoringCase(CARD_NAME).doesNotContainIgnoringCase(SAVINGS_NAME)
                .doesNotContainIgnoringCase(INSTITUTION).doesNotContainIgnoringCase("Lastfour");
    }
}

package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.support.AiIntegrationTest;
import com.finalysis.support.FakeChatModel;
import com.finalysis.support.FakeEmbeddingModel;
import com.finalysis.support.ImportTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 7 step 7d: the Import page's add-account form asks for the last four, so the path it starts is checked end to
 * end (SEC-01): accounts created with {@code POST /api/accounts} as the page sends them, a sample statement imported
 * with the multipart request the page sends, then a categorization run through {@code POST /api/categorization/run}
 * with AI on and fake models. Synthetic rows added to the imported statement carry each account's last four in the
 * shapes descriptions use; no prompt and no embedding input may contain any of them, nor the names or institutions.
 * Chat's side is {@code com.finalysis.chat.outbound.LastFourChatPayloadTest}.
 */
@AiIntegrationTest
@AutoConfigureMockMvc
class LastFourOutboundTest {

    /** The sample card's ACCTID ends 2001; the OFX import checks the account's last four against it. */
    private static final String CARD_LAST_FOUR = "2001";
    private static final String SAVINGS_LAST_FOUR = "7364";
    private static final String CARD_NAME = "Lastfour Rewards Card";
    private static final String SAVINGS_NAME = "Lastfour Rainy Day";
    private static final String INSTITUTION = "Quorvale Federal";

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final MerchantCleaner cleaner;
    private final FakeChatModel model;
    private final FakeEmbeddingModel embeddings;
    private final ImportTestSupport support;
    private final List<Long> accounts = new ArrayList<>();

    LastFourOutboundTest(MockMvc mvc, JdbcTemplate jdbc, JsonMapper json, MerchantCleaner cleaner, FakeChatModel model,
                         FakeEmbeddingModel embeddings, AccountRepository accountRepository) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.json = json;
        this.cleaner = cleaner;
        this.model = model;
        this.embeddings = embeddings;
        this.support = new ImportTestSupport(mvc, accountRepository, jdbc);
    }

    @BeforeEach
    void reset() {
        model.reset();
        embeddings.reset();
    }

    @AfterEach
    void cleanUp() {
        model.reset();
        embeddings.reset();
        jdbc.update("DELETE FROM merchant_embedding");
        for (long id : accounts) {
            jdbc.update("DELETE FROM txn WHERE account_id = ?", id);
            jdbc.update("DELETE FROM statement WHERE account_id = ?", id);
            jdbc.update("DELETE FROM account WHERE id = ?", id);
        }
    }

    /** The add-account form's request body. */
    private long createAccount(String name, String type, String lastFour) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("name", name, "institution", INSTITUTION, "type", type,
                "lastFour", lastFour));
        long id = json.readTree(mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        accounts.add(id);
        return id;
    }

    private void addRow(long accountId, long statementId, String description, String amount) {
        jdbc.update("""
                INSERT INTO txn (account_id, statement_id, txn_date, raw_description, merchant, amount, source_section,
                                 fingerprint)
                VALUES (?, ?, DATE '2026-08-20', ?, ?, ?::numeric, 'WITHDRAWAL', ?)""",
                accountId, statementId, description, cleaner.clean(description).merchant(), amount,
                "fp-" + UUID.randomUUID());
    }

    @Test
    void noAccountsLastFourReachesAnyCategorizationPayload() throws Exception {
        long card = createAccount(CARD_NAME, "CREDIT_CARD", CARD_LAST_FOUR);
        createAccount(SAVINGS_NAME, "SAVINGS", SAVINGS_LAST_FOUR);
        long statementId = json.readTree(support.upload(card, "apex-visa_2026-08.qfx",
                        ImportTestSupport.sample("ofx/apex-visa_2026-08.qfx"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("statementId").asLong();
        addRow(card, statementId, "QUILLFEATHER EMPORIUM CARD ENDING " + CARD_LAST_FOUR, "-12.75");
        addRow(card, statementId, "BRAMBLEWICK TEAROOM XXXX" + CARD_LAST_FOUR, "-18.40");
        addRow(card, statementId, "ZORVEX KIOSK " + SAVINGS_LAST_FOUR, "-7.10");
        addRow(card, statementId, "PELLWORTH TRANSFER *" + SAVINGS_LAST_FOUR + " ONLINE", "-40.00");

        mvc.perform(post("/api/categorization/run")).andExpect(status().isOk());

        assertThat(model.prompts()).as("the run sent the synthetic rows").isNotEmpty();
        StringBuilder sent = new StringBuilder();
        model.prompts().forEach(prompt -> prompt.getInstructions().stream().map(Message::getText)
                .forEach(text -> sent.append(text).append('\n')));
        embeddings.inputs().forEach(text -> sent.append(text).append('\n'));
        String all = sent.toString();
        assertThat(all).contains("QUILLFEATHER EMPORIUM").contains("ZORVEX KIOSK");
        assertThat(all).doesNotContain(CARD_LAST_FOUR).doesNotContain(SAVINGS_LAST_FOUR)
                .doesNotContain("4999000011112001")
                .doesNotContainIgnoringCase(CARD_NAME).doesNotContainIgnoringCase(SAVINGS_NAME)
                .doesNotContainIgnoringCase(INSTITUTION).doesNotContainIgnoringCase("Lastfour");
    }
}

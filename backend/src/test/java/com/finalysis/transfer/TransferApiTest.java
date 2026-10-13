package com.finalysis.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.support.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The transfer actions over the API (ING-05, docs/plans/phase-3c.md §3): every endpoint, its errors,
 * and how the actions interact with detection (rejections, exclusions, one-sided completion).
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional // each test's rows and links roll back
class TransferApiTest {

    private static final LocalDate DAY = LocalDate.of(2026, 4, 10);
    private static final String PROBLEM = "urn:finalysis:problem:";
    private static final String DESCRIPTION = "NORTHFIELD ONLINE TRANSFER"; // every TransferRows txn has it

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final TransferRows rows;

    private long checking;
    private long savings;
    private long card;

    TransferApiTest(MockMvc mockMvc, JsonMapper jsonMapper, JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.rows = new TransferRows(jdbc);
    }

    @BeforeEach
    void setUp() {
        checking = rows.account("CHECKING", "2001");
        savings = rows.account("SAVINGS", "2002");
        card = rows.account("CREDIT_CARD", "2003");
    }

    // ------------------------------------------------------------------ list and get

    @Test
    void listsLinksNewestEffectiveDateFirstWithBothSides() throws Exception {
        long earlyOut = rows.txn(checking, "-250.00", DAY);
        long earlyIn = rows.txn(savings, "250.00", DAY.plusDays(1));
        long lateOut = rows.txn(checking, "-410.25", DAY.plusDays(10));
        long lateIn = rows.txn(card, "410.25", DAY.plusDays(8), DAY.plusDays(12), "PAYMENT");
        detect();

        mockMvc.perform(get("/api/transfers").param("accountId", String.valueOf(checking)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].out.id").value(lateOut))
                .andExpect(jsonPath("$[0].in.id").value(lateIn))
                .andExpect(jsonPath("$[0].daysApart").value(2)) // the card's post date counts, not its txn date
                .andExpect(jsonPath("$[1].out.id").value(earlyOut))
                .andExpect(jsonPath("$[1].in.id").value(earlyIn))
                .andExpect(jsonPath("$[1].status").value("MATCHED"))
                .andExpect(jsonPath("$[1].method").value("AUTO"))
                .andExpect(jsonPath("$[1].confirmedAt").value(nullValue()))
                .andExpect(jsonPath("$[1].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[1].daysApart").value(1));
    }

    @Test
    void aLinkShowsBothSidesWithMoneyAsStrings() throws Exception {
        long out = rows.txn(checking, "-250.00", DAY);
        long in = rows.txn(savings, "250.00", DAY);
        long statementId = jdbc.queryForObject("SELECT statement_id FROM txn WHERE id = ?", Long.class, out);
        long id = linkByUser(out, in);

        mockMvc.perform(get("/api/transfers/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.out.id").value(out))
                .andExpect(jsonPath("$.out.accountId").value(checking))
                .andExpect(jsonPath("$.out.accountName").value("Test"))
                .andExpect(jsonPath("$.out.accountType").value("CHECKING"))
                .andExpect(jsonPath("$.out.statementId").value(statementId))
                .andExpect(jsonPath("$.out.txnDate").value("2026-04-10"))
                .andExpect(jsonPath("$.out.postDate").value(nullValue()))
                .andExpect(jsonPath("$.out.amount").value("-250.00"))
                .andExpect(jsonPath("$.out.sourceSection").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.out.rawDescription").value(DESCRIPTION))
                .andExpect(jsonPath("$.in.amount").value("250.00"))
                .andExpect(jsonPath("$.in.accountType").value("SAVINGS"));
    }

    @Test
    void filtersByStatusConfirmationAndAccount() throws Exception {
        long autoOut = rows.txn(checking, "-250.00", DAY);
        long autoIn = rows.txn(savings, "250.00", DAY);
        detect();
        long auto = linkId(autoOut);
        long userOut = rows.txn(checking, "-75.00", DAY);
        long user = linkByUser(userOut, rows.txn(card, "75.00", DAY, "PAYMENT"));
        long oneSided = oneSided("outTxnId", rows.txn(savings, "-160.00", DAY));

        assertThat(ids(get("/api/transfers").param("accountId", String.valueOf(checking)))).containsExactly(user, auto);
        assertThat(ids(get("/api/transfers").param("accountId", String.valueOf(savings)))).containsExactly(oneSided, auto);
        assertThat(ids(get("/api/transfers").param("accountId", String.valueOf(card)))).containsExactly(user);
        assertThat(ids(get("/api/transfers").param("status", "UNMATCHED"))).contains(oneSided).doesNotContain(auto, user);
        assertThat(ids(get("/api/transfers").param("confirmed", "false"))).contains(auto).doesNotContain(user, oneSided);
        assertThat(ids(get("/api/transfers").param("confirmed", "true"))).contains(user, oneSided).doesNotContain(auto);
        assertThat(ids(get("/api/transfers").param("status", "MATCHED").param("confirmed", "true")
                .param("accountId", String.valueOf(checking)))).containsExactly(user);
        assertThat(rows.isTransfer(autoIn)).isTrue();
    }

    @Test
    void rejectsABadFilter() throws Exception {
        mockMvc.perform(get("/api/transfers").param("status", "PENDING")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/transfers").param("confirmed", "maybe")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/transfers").param("accountId", "checking")).andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownLinkIsNotFound() throws Exception {
        mockMvc.perform(get("/api/transfers/{id}", Long.MAX_VALUE)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/transfers/{id}/confirm", Long.MAX_VALUE)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/transfers/{id}", Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    /** Ids are digits only, so /detect is never read as a link id. */
    @Test
    void detectIsNotALinkId() throws Exception {
        mockMvc.perform(delete("/api/transfers/detect")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(get("/api/transfers/abc")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ link by hand

    /** No window and no account-type rule: a card → checking pair 20 days apart is the user's call. */
    @Test
    void linksTwoTxnsByHand() throws Exception {
        long out = rows.txn(card, "-75.00", DAY, "PURCHASE");
        long in = rows.txn(checking, "75.00", DAY.plusDays(20));

        String location = create(Map.of("outTxnId", out, "inTxnId", in))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("MATCHED"))
                .andExpect(jsonPath("$.method").value("USER"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.daysApart").value(20))
                .andExpect(jsonPath("$.out.id").value(out))
                .andExpect(jsonPath("$.in.id").value(in))
                .andReturn().getResponse().getHeader("Location");

        long id = linkId(out);
        assertThat(location).endsWith("/api/transfers/" + id);
        assertThat(rows.isTransfer(out)).isTrue();
        assertThat(rows.isTransfer(in)).isTrue();
    }

    @Test
    void linkingByHandClearsAnExclusionAndARejection() throws Exception {
        long out = rows.txn(checking, "-250.00", DAY);
        long in = rows.txn(savings, "250.00", DAY);
        rows.exclude(out);
        rows.exclude(in);
        rows.reject(out, in);

        create(Map.of("outTxnId", out, "inTxnId", in)).andExpect(status().isCreated());

        assertThat(excluded(out)).isFalse();
        assertThat(excluded(in)).isFalse();
        assertThat(rejected(out, in)).isFalse();
        assertThat(rows.isTransfer(out)).isTrue();
    }

    @Test
    void aLinkNeedsEqualAndOppositeAmountsInDifferentAccounts() throws Exception {
        long out = rows.txn(checking, "-250.00", DAY);
        long otherOut = rows.txn(checking, "-250.00", DAY);
        long sameAccountIn = rows.txn(checking, "250.00", DAY);
        long unequalIn = rows.txn(savings, "249.99", DAY);
        long zero = rows.txn(savings, "0.00", DAY);
        long in = rows.txn(savings, "250.00", DAY);

        expectInvalid(Map.of("outTxnId", out, "inTxnId", sameAccountIn), "different accounts");
        expectInvalid(Map.of("outTxnId", out, "inTxnId", unequalIn), "equal and opposite");
        expectInvalid(Map.of("outTxnId", out, "inTxnId", otherOut), "money in");
        expectInvalid(Map.of("outTxnId", in, "inTxnId", out), "money out");
        expectInvalid(Map.of("outTxnId", out, "inTxnId", zero), "money in");
        expectInvalid(Map.of("outTxnId", zero), "money out");
        expectInvalid(Map.of(), "Give outTxnId, inTxnId, or both");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_link WHERE out_txn_id = ? OR in_txn_id = ?",
                Integer.class, out, out)).isZero();
    }

    @Test
    void aTxnOnALinkCantBeLinkedAgain() throws Exception {
        long out = rows.txn(checking, "-250.00", DAY);
        long in = rows.txn(savings, "250.00", DAY);
        long otherIn = rows.txn(card, "250.00", DAY, "PAYMENT");
        long otherOut = rows.txn(checking, "-250.00", DAY);
        long linkId = linkByUser(out, in);

        expectConflict(Map.of("outTxnId", out, "inTxnId", otherIn), linkId);
        expectConflict(Map.of("outTxnId", otherOut, "inTxnId", in), linkId);
        expectConflict(Map.of("outTxnId", out), linkId);
        expectConflict(Map.of("inTxnId", in), linkId);
    }

    @Test
    void anUnknownTxnIsNotFound() throws Exception {
        long out = rows.txn(checking, "-250.00", DAY);

        create(Map.of("outTxnId", out, "inTxnId", Long.MAX_VALUE)).andExpect(status().isNotFound());
        create(Map.of("inTxnId", Long.MAX_VALUE)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/transactions/{id}/not-transfer", Long.MAX_VALUE)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/transactions/{id}/not-transfer", Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void rejectsUnknownFieldsAndJunkBodies() throws Exception {
        long out = rows.txn(checking, "-250.00", DAY);

        create(Map.of("outTxnId", out, "amount", "250.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
        mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content("{\"outTxnId\": \"x\"}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ one-sided

    @Test
    void marksAnOutgoingTxnOneSided() throws Exception {
        long out = rows.txn(checking, "-160.00", DAY);
        rows.exclude(out);

        create(Map.of("outTxnId", out))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/transfers/" + linkId(out))))
                .andExpect(jsonPath("$.status").value("UNMATCHED"))
                .andExpect(jsonPath("$.method").value("USER"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.daysApart").value(nullValue()))
                .andExpect(jsonPath("$.out.id").value(out))
                .andExpect(jsonPath("$.in").value(nullValue()));

        assertThat(rows.isTransfer(out)).isTrue();
        assertThat(excluded(out)).isFalse(); // the explicit action wins over "not a transfer"
    }

    /** V9 makes the out side nullable, so money in from an account not in the app can be marked too. */
    @Test
    void marksAnIncomingTxnOneSided() throws Exception {
        long in = rows.txn(savings, "1200.00", DAY);

        create(Map.of("inTxnId", in))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNMATCHED"))
                .andExpect(jsonPath("$.out").value(nullValue()))
                .andExpect(jsonPath("$.in.id").value(in));

        assertThat(rows.isTransfer(in)).isTrue();
    }

    @Test
    void aOneSidedTxnMustHaveTheRightSign() throws Exception {
        expectInvalid(Map.of("outTxnId", rows.txn(checking, "160.00", DAY)), "money out");
        expectInvalid(Map.of("inTxnId", rows.txn(checking, "-160.00", DAY)), "money in");
    }

    /** §2.4: the pairing is new and unreviewed, so the completed link is AUTO and unconfirmed. */
    @Test
    void completingAOneSidedLinkClearsItsConfirmation() throws Exception {
        long out = rows.txn(checking, "-250.00", DAY);
        long id = oneSided("outTxnId", out);
        long in = rows.txn(savings, "250.00", DAY.plusDays(2));

        mockMvc.perform(post("/api/transfers/detect"))
                .andExpect(jsonPath("$.unmatchedCompleted").value(1));

        mockMvc.perform(get("/api/transfers/{id}", id))
                .andExpect(jsonPath("$.status").value("MATCHED"))
                .andExpect(jsonPath("$.method").value("AUTO"))
                .andExpect(jsonPath("$.confirmedAt").value(nullValue()))
                .andExpect(jsonPath("$.in.id").value(in));
    }

    // ------------------------------------------------------------------ confirm and unlink

    @Test
    void confirmIsIdempotentAndKeepsTheFirstTime() throws Exception {
        long out = rows.txn(checking, "-250.00", DAY);
        rows.txn(savings, "250.00", DAY);
        detect();
        long id = linkId(out);

        String first = mockMvc.perform(post("/api/transfers/{id}/confirm", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("AUTO"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/transfers/{id}/confirm", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(jsonMapper.readTree(second).get("confirmedAt"))
                .isEqualTo(jsonMapper.readTree(first).get("confirmedAt"));
        assertThat(jdbc.queryForObject("SELECT confirmed_at IS NOT NULL FROM transfer_link WHERE id = ?",
                Boolean.class, id)).isTrue();
    }

    @Test
    void unlinkingRemembersThePairSoDetectionNeverRelinksIt() throws Exception {
        long out = rows.txn(checking, "-250.00", DAY);
        long in = rows.txn(savings, "250.00", DAY);
        detect();

        mockMvc.perform(delete("/api/transfers/{id}", linkId(out))).andExpect(status().isNoContent());

        assertThat(rejected(out, in)).isTrue();
        assertThat(rows.isTransfer(out)).isFalse();
        assertThat(rows.isTransfer(in)).isFalse();
        assertThat(detect()).isEqualTo(new DetectionResult(0, 0, 0));
        assertThat(rows.isTransfer(out)).isFalse();
    }

    /** A rejection covers that pair only: either txn can still match something else. */
    @Test
    void anUnlinkedTxnCanStillMatchAnotherCounterpart() throws Exception {
        long out = rows.txn(checking, "-250.00", DAY);
        long in = rows.txn(savings, "250.00", DAY);
        detect();
        mockMvc.perform(delete("/api/transfers/{id}", linkId(out))).andExpect(status().isNoContent());
        long cardIn = rows.txn(card, "250.00", DAY.plusDays(1), "PAYMENT");

        assertThat(detect()).isEqualTo(new DetectionResult(1, 0, 0));
        assertThat(jdbc.queryForObject("SELECT in_txn_id FROM transfer_link WHERE out_txn_id = ?", Long.class, out))
                .isEqualTo(cardIn);
        assertThat(rows.isTransfer(in)).isFalse();
    }

    @Test
    void unlinkingAOneSidedLinkRecordsNoRejection() throws Exception {
        long out = rows.txn(checking, "-160.00", DAY);
        long id = oneSided("outTxnId", out);

        mockMvc.perform(delete("/api/transfers/{id}", id)).andExpect(status().isNoContent());

        assertThat(rows.isTransfer(out)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_rejection WHERE out_txn_id = ?",
                Integer.class, out)).isZero();
    }

    /** Manual linking deletes the rejection that unlinking recorded (§3: the explicit action wins). */
    @Test
    void aRejectedPairCanStillBeLinkedByHand() throws Exception {
        long out = rows.txn(checking, "-250.00", DAY);
        long in = rows.txn(savings, "250.00", DAY);
        detect();
        mockMvc.perform(delete("/api/transfers/{id}", linkId(out))).andExpect(status().isNoContent());

        create(Map.of("outTxnId", out, "inTxnId", in)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.method").value("USER"));

        assertThat(rejected(out, in)).isFalse();
        assertThat(rows.isTransfer(in)).isTrue();
    }

    // ------------------------------------------------------------------ not a transfer

    @Test
    void notATransferRemovesTheLinkAndBlocksDetection() throws Exception {
        long out = rows.txn(checking, "-250.00", DAY);
        long in = rows.txn(savings, "250.00", DAY);
        detect();
        long id = linkId(out);
        long otherIn = rows.txn(card, "250.00", DAY, "PAYMENT");

        mockMvc.perform(post("/api/transactions/{id}/not-transfer", out))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txnId").value(out))
                .andExpect(jsonPath("$.transferExcluded").value(true))
                .andExpect(jsonPath("$.removedLinkId").value(id));

        assertThat(excluded(out)).isTrue();
        assertThat(rejected(out, in)).isTrue();
        assertThat(rows.isTransfer(out)).isFalse();
        assertThat(rows.isTransfer(in)).isFalse();
        assertThat(detect()).isEqualTo(new DetectionResult(0, 0, 0)); // neither the old pair nor otherIn
        assertThat(rows.isTransfer(otherIn)).isFalse();
    }

    @Test
    void undoingNotATransferLetsTheNextRunLinkIt() throws Exception {
        long out = rows.txn(checking, "-250.00", DAY);
        long in = rows.txn(savings, "250.00", DAY);

        mockMvc.perform(post("/api/transactions/{id}/not-transfer", out))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removedLinkId").value(nullValue()));
        assertThat(detect()).isEqualTo(new DetectionResult(0, 0, 0));

        mockMvc.perform(delete("/api/transactions/{id}/not-transfer", out)).andExpect(status().isNoContent());
        assertThat(excluded(out)).isFalse();
        assertThat(rows.isTransfer(out)).isFalse(); // not until detection runs

        assertThat(detect()).isEqualTo(new DetectionResult(1, 0, 0));
        assertThat(rows.isTransfer(in)).isTrue();
    }

    @Test
    void notATransferIsIdempotent() throws Exception {
        long out = rows.txn(checking, "-250.00", DAY);

        mockMvc.perform(post("/api/transactions/{id}/not-transfer", out)).andExpect(status().isOk());
        mockMvc.perform(post("/api/transactions/{id}/not-transfer", out))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferExcluded").value(true));
        mockMvc.perform(delete("/api/transactions/{id}/not-transfer", out)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/transactions/{id}/not-transfer", out)).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions create(Map<String, ?> body) throws Exception {
        return mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    private long linkByUser(long out, long in) throws Exception {
        return id(create(Map.of("outTxnId", out, "inTxnId", in)).andExpect(status().isCreated()));
    }

    private long oneSided(String side, long txnId) throws Exception {
        return id(create(Map.of(side, txnId)).andExpect(status().isCreated()));
    }

    /** 422 naming the rule; the detail never quotes a description or an amount (SEC-10). */
    private void expectInvalid(Map<String, ?> body, String rule) throws Exception {
        create(body)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.type").value(PROBLEM + "transfer-invalid"))
                .andExpect(jsonPath("$.title").value("Invalid transfer"))
                .andExpect(jsonPath("$.detail").value(containsString(rule)))
                .andExpect(jsonPath("$.detail").value(not(containsString(DESCRIPTION))))
                .andExpect(jsonPath("$.detail").value(not(containsString("250"))));
    }

    private void expectConflict(Map<String, ?> body, long linkId) throws Exception {
        create(body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "transfer-conflict"))
                .andExpect(jsonPath("$.linkId").value(linkId))
                .andExpect(jsonPath("$.detail").value(containsString("transfer link " + linkId)))
                .andExpect(jsonPath("$.detail").value(not(containsString(DESCRIPTION))));
    }

    private DetectionResult detect() throws Exception {
        String body = mockMvc.perform(post("/api/transfers/detect")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readValue(body, DetectionResult.class);
    }

    private long id(ResultActions created) throws Exception {
        return jsonMapper.readTree(created.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private List<Long> ids(RequestBuilder request) throws Exception {
        JsonNode body = jsonMapper.readTree(mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        return body.valueStream().map(link -> link.get("id").asLong()).toList();
    }

    private long linkId(long txnId) {
        return jdbc.queryForObject("SELECT id FROM transfer_link WHERE out_txn_id = ? OR in_txn_id = ?",
                Long.class, txnId, txnId);
    }

    private boolean excluded(long txnId) {
        return jdbc.queryForObject("SELECT transfer_excluded FROM txn WHERE id = ?", Boolean.class, txnId);
    }

    private boolean rejected(long out, long in) {
        return jdbc.queryForObject("SELECT count(*) FROM transfer_rejection WHERE out_txn_id = ? AND in_txn_id = ?",
                Integer.class, out, in) == 1;
    }
}

package com.finalysis.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.categorize.CategorizationService;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import com.finalysis.transfer.TransferRows;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The review queue and its actions (ING-07, ING-13; docs/plans/phase-3c.md §4): mark a NEEDS_REVIEW
 * statement reviewed, the late-arrival re-queue, reading and dismissing unparsed uploads, and
 * {@code GET /api/review-queue} with its six kinds, competing candidates (§4.6), possible matches
 * with join and dismiss (§4.5), and suggested one-sided transfers (CAT-08, phase-4.md §8). Tests that import commit for real, and the accounts they create are
 * deleted after each test; tests built from plain SQL rows are {@code @Transactional} and roll back.
 * Queue assertions filter by the test's own accounts, since the queue spans every account.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ReviewQueueApiTest {

    private static final String PROBLEM = "urn:finalysis:problem:";
    private static final String OFX_APRIL = "ofx/northfield-checking_2026-04.ofx";
    private static final String OFX_MAY = "ofx/northfield-checking_2026-05.ofx";
    private static final String LEDGERBAL_MISMATCH = "edge-cases/ofx-ledgerbal-mismatch.ofx"; // a May statement
    private static final String UNRECOGNIZED = "pdf/edge-cases/pdf-unrecognized-layout.pdf";
    private static final String CHECKING_CSV = "statements/northfield-checking_2026-04.csv";
    private static final String APEX_QFX = "ofx/apex-visa_2026-04.qfx";
    private static final LocalDate DAY = LocalDate.of(2026, 4, 10);
    private static final String DESCRIPTION = "NORTHFIELD ONLINE TRANSFER"; // every TransferRows txn has it

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final ImportTestSupport support;
    private final TransferRows rows;
    private final CategorizationService categorization;

    ReviewQueueApiTest(MockMvc mockMvc, JsonMapper jsonMapper, AccountRepository accounts, JdbcTemplate jdbc,
                       CategorizationService categorization) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.rows = new TransferRows(jdbc);
        this.categorization = categorization;
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    // ------------------------------------------------------------------ mark reviewed (§4.2)

    @Test
    void reviewingKeepsTheStatusAndWarnings() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        importOk(checking, OFX_APRIL, Map.of());
        JsonNode may = importOk(checking, LEDGERBAL_MISMATCH, Map.of());
        long id = may.get("statementId").asLong();
        assertThat(may.get("status").asString()).isEqualTo("NEEDS_REVIEW");

        JsonNode reviewed = json(mockMvc.perform(post("/api/statements/{id}/review", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.balanceCheck").value("FAILED"))
                .andExpect(jsonPath("$.reviewedAt").isNotEmpty()));

        assertThat(reviewed.get("warnings")).isEqualTo(may.get("warnings"));
        mockMvc.perform(get("/api/statements/{id}", id))
                .andExpect(jsonPath("$.reviewedAt").value(reviewed.get("reviewedAt").asString()));
        mockMvc.perform(get("/api/accounts/{id}/statements", checking).param("status", "NEEDS_REVIEW"))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].reviewedAt").isNotEmpty());
    }

    @Test
    void reviewingAgainKeepsTheFirstTime() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        importOk(checking, OFX_APRIL, Map.of());
        long id = importOk(checking, LEDGERBAL_MISMATCH, Map.of()).get("statementId").asLong();

        String first = review(id).get("reviewedAt").asString();

        assertThat(review(id).get("reviewedAt").asString()).isEqualTo(first);
    }

    @Test
    void onlyANeedsReviewStatementCanBeReviewed() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long april = importOk(checking, OFX_APRIL, Map.of()).get("statementId").asLong(); // IMPORTED
        long may = importOk(checking, OFX_MAY, Map.of()).get("statementId").asLong(); // RECONCILED

        for (long id : List.of(april, may)) {
            mockMvc.perform(post("/api/statements/{id}/review", id))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value(PROBLEM + "not-reviewable"))
                    .andExpect(jsonPath("$.title").value("Statement does not need review"));
        }
        mockMvc.perform(post("/api/statements/{id}/review", may))
                .andExpect(jsonPath("$.detail").value("Statement " + may
                        + " is RECONCILED; only NEEDS_REVIEW statements can be reviewed"));
        assertThat(support.count("SELECT count(*) FROM statement WHERE account_id = ? AND reviewed_at IS NOT NULL",
                checking)).isZero();
        mockMvc.perform(post("/api/statements/{id}/review", Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    /** §4.2: a late arrival's re-check that adds a warning puts a reviewed statement back in the queue. */
    @Test
    void aLateArrivalThatAddsAWarningClearsTheReview() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        // Rows after the 20th fall outside this period: NEEDS_REVIEW, and unverified (no April yet).
        long may = importOk(checking, LEDGERBAL_MISMATCH, Map.of("periodEnd", "2026-05-20")).get("statementId").asLong();
        JsonNode reviewed = review(may);
        assertThat(reviewed.get("balanceCheck").asString()).isEqualTo("UNVERIFIED");

        importOk(checking, OFX_APRIL, Map.of()); // continuity now runs, and fails

        JsonNode mayNow = statement(may);
        assertThat(mayNow.get("balanceCheck").asString()).isEqualTo("FAILED");
        assertThat(mayNow.get("reviewedAt").isNull()).isTrue();
        assertThat(mayNow.get("warnings").size()).isEqualTo(reviewed.get("warnings").size()); // unverified → failed
    }

    @Test
    void aLateArrivalThatPassesKeepsTheReview() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long may = importOk(checking, OFX_MAY, Map.of("periodEnd", "2026-05-20")).get("statementId").asLong();
        String reviewedAt = review(may).get("reviewedAt").asString();

        importOk(checking, OFX_APRIL, Map.of()); // continuity passes: only the unverified warning goes

        JsonNode mayNow = statement(may);
        assertThat(mayNow.get("balanceCheck").asString()).isEqualTo("PASSED");
        assertThat(mayNow.get("status").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(mayNow.get("reviewedAt").asString()).isEqualTo(reviewedAt);
    }

    // ------------------------------------------------------------------ unparsed uploads (§4.3)

    @Test
    void anUnparsedUploadCanBeReadAtItsLocation() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        ResultActions accepted = support.upload(checking, "card.pdf", ImportTestSupport.sample(UNRECOGNIZED))
                .andExpect(status().isAccepted());
        long id = json(accepted).get("id").asLong();
        String location = accepted.andExpect(header().string("Location", endsWith("/api/unparsed-uploads/" + id)))
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.accountId").value(checking))
                .andExpect(jsonPath("$.reason").value("UNRECOGNIZED_LAYOUT"))
                .andExpect(jsonPath("$.fileName").value("card.pdf"))
                .andExpect(jsonPath("$.state").value("OPEN"))
                .andExpect(jsonPath("$.resolvedAt").value(nullValue()))
                .andExpect(jsonPath("$.resolvedStatementId").value(nullValue()));
        mockMvc.perform(get("/api/unparsed-uploads/{id}", Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void dismissingAnUploadResolvesItWithoutAStatement() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long id = upload(checking);

        mockMvc.perform(post("/api/unparsed-uploads/{id}/dismiss", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.state").value("DISMISSED"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty())
                .andExpect(jsonPath("$.resolvedStatementId").value(nullValue()))
                .andExpect(jsonPath("$.detail").value("Dismissed; nothing was imported."));

        Map<String, Object> row = jdbc.queryForMap("SELECT resolved_at, resolved_statement_id FROM unparsed_upload "
                + "WHERE id = ?", id);
        assertThat(row.get("resolved_at")).isNotNull();
        assertThat(row.get("resolved_statement_id")).isNull();
        mockMvc.perform(get("/api/unparsed-uploads/{id}", id)).andExpect(jsonPath("$.state").value("DISMISSED"));
    }

    @Test
    void anUploadCanBeDismissedOnce() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long id = upload(checking);
        mockMvc.perform(post("/api/unparsed-uploads/{id}/dismiss", id)).andExpect(status().isOk());
        String resolvedAt = jdbc.queryForObject("SELECT resolved_at::text FROM unparsed_upload WHERE id = ?",
                String.class, id);

        mockMvc.perform(post("/api/unparsed-uploads/{id}/dismiss", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "already-resolved"))
                .andExpect(jsonPath("$.title").value("Unparsed upload already resolved"))
                .andExpect(jsonPath("$.detail").value("Unparsed upload " + id + " is already DISMISSED"));

        assertThat(jdbc.queryForObject("SELECT resolved_at::text FROM unparsed_upload WHERE id = ?", String.class, id))
                .isEqualTo(resolvedAt);
        mockMvc.perform(post("/api/unparsed-uploads/{id}/dismiss", Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    /** V8: a dismissed upload never reopens; uploading the file again gives a new open row. */
    @Test
    void reuploadingADismissedFileOpensANewUpload() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long first = upload(checking);
        mockMvc.perform(post("/api/unparsed-uploads/{id}/dismiss", first)).andExpect(status().isOk());

        long second = upload(checking);

        assertThat(second).isNotEqualTo(first);
        mockMvc.perform(get("/api/unparsed-uploads/{id}", second)).andExpect(jsonPath("$.state").value("OPEN"));
        mockMvc.perform(get("/api/unparsed-uploads/{id}", first)).andExpect(jsonPath("$.state").value("DISMISSED"));
    }

    // ------------------------------------------------------------------ the queue (§4.1)

    /** One item of every kind, all touching the checking account. */
    @Test
    @Transactional
    void listsEveryKindAcrossAccounts() throws Exception {
        Accounts a = sqlAccounts();
        long statement = rows.statement(a.checking(), "NEEDS_REVIEW");
        long upload = sqlUpload(a.checking());
        long groupOut = rows.txn(a.checking(), "-200.00", DAY);
        rows.txn(a.savings(), "200.00", DAY);
        rows.txn(a.card(), "200.00", DAY.plusDays(1), "PAYMENT");
        long linkOut = rows.txn(a.checking(), "-300.00", DAY);
        long link = rows.autoLink(linkOut, rows.txn(a.savings(), "300.00", DAY));
        rows.txn(a.card(), "300.00", DAY.plusDays(2), "PAYMENT");
        long outLink = rows.userLink(rows.txn(a.checking(), "-160.00", DAY), null, "UNMATCHED");
        long inLink = rows.userLink(null, rows.txn(a.savings(), "160.00", DAY), "UNMATCHED");

        JsonNode queue = queue(Map.of("accountId", a.checking()));

        assertThat(queue.get("counts")).isEqualTo(counts(1, 1, 1, 1, 1));
        assertThat(kinds(queue)).containsExactlyInAnyOrder(
                "STATEMENT", "UNPARSED_UPLOAD", "TRANSFER_CANDIDATES", "COMPETING_CANDIDATE", "POSSIBLE_MATCH");
        JsonNode statementItem = item(queue, "STATEMENT");
        assertThat(statementItem.get("statementId").asLong()).isEqualTo(statement);
        assertThat(statementItem.get("accountName").asString()).isEqualTo("Test");
        assertThat(statementItem.get("fileName").asString()).isEqualTo("test.csv");
        assertThat(statementItem.get("balanceCheck").asString()).isEqualTo("UNVERIFIED");
        assertThat(statementItem.get("warnings").isArray()).isTrue();
        assertThat(statementItem.get("since").isNull()).isFalse();
        assertThat(item(queue, "UNPARSED_UPLOAD").get("uploadId").asLong()).isEqualTo(upload);
        assertThat(item(queue, "UNPARSED_UPLOAD").get("reason").asString()).isEqualTo("UNRECOGNIZED_LAYOUT");
        assertThat(item(queue, "TRANSFER_CANDIDATES").get("key").asString()).isEqualTo("txn-" + groupOut);
        assertThat(item(queue, "COMPETING_CANDIDATE").get("key").asString()).isEqualTo("link-" + link);
        assertThat(item(queue, "POSSIBLE_MATCH").get("key").asString())
                .isEqualTo("links-" + outLink + "-" + inLink);

        // The card touches only the group and the competing candidate.
        assertThat(queue(Map.of("accountId", a.card())).get("counts")).isEqualTo(counts(0, 0, 1, 1, 0));
        assertThat(kinds(queue(Map.of()))).contains("STATEMENT", "POSSIBLE_MATCH"); // no filter: every account
    }

    @Test
    @Transactional
    void listsNewestFirstAndTheKindFilterKeepsTheCounts() throws Exception {
        Accounts a = sqlAccounts();
        long statement = rows.statement(a.checking(), "NEEDS_REVIEW");
        long upload = sqlUpload(a.checking());
        long outLink = rows.userLink(rows.txn(a.checking(), "-160.00", DAY), null, "UNMATCHED");
        long inLink = rows.userLink(null, rows.txn(a.savings(), "160.00", DAY), "UNMATCHED");
        jdbc.update("UPDATE statement SET imported_at = now() - interval '2 days' WHERE id = ?", statement);
        jdbc.update("UPDATE unparsed_upload SET created_at = now() - interval '3 days' WHERE id = ?", upload);
        jdbc.update("UPDATE transfer_link SET created_at = now() - interval '1 day' WHERE id IN (?, ?)", outLink, inLink);

        JsonNode queue = queue(Map.of("accountId", a.checking()));
        assertThat(kinds(queue)).containsExactly("POSSIBLE_MATCH", "STATEMENT", "UNPARSED_UPLOAD");

        JsonNode statements = queue(Map.of("accountId", a.checking(), "kind", "STATEMENT"));
        assertThat(kinds(statements)).containsExactly("STATEMENT");
        assertThat(statements.get("counts")).isEqualTo(counts(1, 1, 0, 0, 1));
    }

    @Test
    void rejectsAnUnknownKindAndGivesAnEmptyQueueForAnUnknownAccount() throws Exception {
        mockMvc.perform(get("/api/review-queue").param("kind", "EVERYTHING")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/review-queue").param("accountId", "abc")).andExpect(status().isBadRequest());

        JsonNode queue = queue(Map.of("accountId", Long.MAX_VALUE));
        assertThat(queue.get("items").size()).isZero();
        assertThat(queue.get("counts")).isEqualTo(counts(0, 0, 0, 0, 0, 0));
    }

    /**
     * A reviewed statement and a dismissed upload leave the queue; a late warning brings the statement back. The
     * statement's unlinked online transfers are suggested one-sided transfers (CAT-08), so only these two kinds are
     * looked at.
     */
    @Test
    void reviewAndDismissRemoveTheirItems() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long may = importOk(checking, LEDGERBAL_MISMATCH, Map.of("periodEnd", "2026-05-20")).get("statementId").asLong();
        long upload = upload(checking);
        assertThat(statementAndUploadKinds(checking)).containsExactlyInAnyOrder("STATEMENT", "UNPARSED_UPLOAD");

        review(may);
        mockMvc.perform(post("/api/unparsed-uploads/{id}/dismiss", upload)).andExpect(status().isOk());
        assertThat(statementAndUploadKinds(checking)).isEmpty();

        importOk(checking, OFX_APRIL, Map.of()); // the re-check fails: May needs review again
        assertThat(statementAndUploadKinds(checking)).containsExactly("STATEMENT");
        JsonNode statements = queue(Map.of("accountId", checking, "kind", "STATEMENT"));
        assertThat(item(statements, "STATEMENT").get("statementId").asLong()).isEqualTo(may);
        assertThat(item(statements, "STATEMENT").get("balanceCheck").asString()).isEqualTo("FAILED");
        assertThat(statements.get("counts").get("suggestedOneSidedTransfers").asInt()).isPositive();
    }

    private List<String> statementAndUploadKinds(long accountId) throws Exception {
        return kinds(queue(Map.of("accountId", accountId))).stream()
                .filter(kind -> kind.equals("STATEMENT") || kind.equals("UNPARSED_UPLOAD"))
                .toList();
    }

    // ------------------------------------------------------------------ transfer candidate groups (§2.3)

    @Test
    @Transactional
    void aCandidateGroupShowsItsTxnsAndPairsAndLeavesOnceLinked() throws Exception {
        Accounts a = sqlAccounts();
        long out = rows.txn(a.checking(), "-200.00", DAY);
        long savingsIn = rows.txn(a.savings(), "200.00", DAY.plusDays(1));
        long cardIn = rows.txn(a.card(), "200.00", DAY.plusDays(3), "PAYMENT");
        jdbc.update("UPDATE txn SET created_at = now() - interval '1 day' WHERE id IN (?, ?)", out, savingsIn);
        detect();

        JsonNode group = item(queue(Map.of("accountId", a.checking())), "TRANSFER_CANDIDATES");
        assertThat(ids(group.get("txns"))).containsExactly(out, savingsIn, cardIn);
        assertThat(group.get("txns").get(0).get("amount").asString()).isEqualTo("-200.00");
        assertThat(group.get("txns").get(0).get("rawDescription").asString()).isEqualTo(DESCRIPTION);
        assertThat(group.get("pairs").toString()).isEqualTo(pairs(out, savingsIn, 1, out, cardIn, 3));
        assertThat(instant(group.get("since"))).isEqualTo(createdAt(cardIn));
        assertThat(rows.isTransfer(out)).isFalse();

        createTransfer("{\"outTxnId\": " + out + ", \"inTxnId\": " + savingsIn + "}");

        assertThat(queue(Map.of("accountId", a.checking())).get("counts").get("transferCandidates").asInt()).isZero();
    }

    // ------------------------------------------------------------------ competing candidates (§4.6)

    @Test
    @Transactional
    void aCounterpartArrivingAfterALinkListsItAsACompetingCandidate() throws Exception {
        Accounts a = sqlAccounts();
        long out = rows.txn(a.checking(), "-300.00", DAY);
        long savingsIn = rows.txn(a.savings(), "300.00", DAY.plusDays(1));
        detect();
        long link = linkOf(out);
        assertThat(queue(Map.of("accountId", a.checking())).get("items").size()).isZero();

        long cardIn = rows.txn(a.card(), "300.00", DAY.plusDays(2), "PAYMENT");
        detect(); // never changes the link

        JsonNode item = item(queue(Map.of("accountId", a.checking())), "COMPETING_CANDIDATE");
        assertThat(item.get("linkId").asLong()).isEqualTo(link);
        assertThat(item.get("out").get("id").asLong()).isEqualTo(out);
        assertThat(item.get("in").get("id").asLong()).isEqualTo(savingsIn);
        assertThat(ids(item.get("competitors"))).containsExactly(cardIn);
        assertThat(item.get("competitors").get(0).get("accountType").asString()).isEqualTo("CREDIT_CARD");
        assertThat(item.get("pairs").toString()).isEqualTo(pairs(out, savingsIn, 1, out, cardIn, 2));
        assertThat(instant(item.get("since"))).isEqualTo(createdAt(cardIn));
        assertThat(rows.isTransfer(cardIn)).isFalse();

        mockMvc.perform(post("/api/transfers/{id}/confirm", link)).andExpect(status().isOk());

        assertThat(queue(Map.of("accountId", a.checking())).get("items").size()).as("confirmed: gone").isZero();
    }

    @Test
    @Transactional
    void userLinksAreNeverCompetingCandidates() throws Exception {
        Accounts a = sqlAccounts();
        createTransfer("{\"outTxnId\": " + rows.txn(a.checking(), "-300.00", DAY)
                + ", \"inTxnId\": " + rows.txn(a.savings(), "300.00", DAY) + "}");
        rows.txn(a.card(), "300.00", DAY.plusDays(1), "PAYMENT");

        assertThat(queue(Map.of("accountId", a.checking())).get("items").size()).isZero();
    }

    /** Unlinking records the rejection; the next detection pairs the freed txn with the competitor. */
    @Test
    @Transactional
    void unlinkingThenDetectingReResolvesIt() throws Exception {
        Accounts a = sqlAccounts();
        long out = rows.txn(a.checking(), "-300.00", DAY);
        rows.txn(a.savings(), "300.00", DAY);
        detect();
        long cardIn = rows.txn(a.card(), "300.00", DAY.plusDays(1), "PAYMENT");

        mockMvc.perform(delete("/api/transfers/{id}", linkOf(out))).andExpect(status().isNoContent());
        detect();

        assertThat(jdbc.queryForObject("SELECT in_txn_id FROM transfer_link WHERE out_txn_id = ?", Long.class, out))
                .isEqualTo(cardIn);
        assertThat(queue(Map.of("accountId", a.checking())).get("items").size()).isZero();
    }

    @Test
    @Transactional
    void markingTheCompetitorNotATransferRemovesTheItem() throws Exception {
        Accounts a = sqlAccounts();
        long out = rows.txn(a.checking(), "-300.00", DAY);
        rows.txn(a.savings(), "300.00", DAY);
        detect();
        long cardIn = rows.txn(a.card(), "300.00", DAY.plusDays(1), "PAYMENT");
        assertThat(kinds(queue(Map.of("accountId", a.card())))).containsExactly("COMPETING_CANDIDATE");

        mockMvc.perform(post("/api/transactions/{id}/not-transfer", cardIn)).andExpect(status().isOk());

        assertThat(kinds(queue(Map.of("accountId", a.card()))))
                .as("no longer competing; uncategorized, so it waits for a category")
                .containsExactly("NOT_TRANSFER_UNCATEGORIZED");
        assertThat(rows.isTransfer(out)).isTrue();
    }

    // ------------------------------------------------------------------ possible matches (§4.5)

    @Test
    @Transactional
    void possibleMatchesAreListedWithinTheWindowOnly() throws Exception {
        Accounts a = sqlAccounts();
        long out = rows.txn(a.checking(), "-160.00", DAY);
        long in = rows.txn(a.savings(), "160.00", DAY.plusDays(5));
        long outLink = rows.userLink(out, null, "UNMATCHED");
        long inLink = rows.userLink(null, in, "UNMATCHED");
        long farOut = rows.userLink(rows.txn(a.checking(), "-90.00", DAY), null, "UNMATCHED");
        rows.userLink(null, rows.txn(a.savings(), "90.00", DAY.plusDays(6)), "UNMATCHED");

        JsonNode queue = queue(Map.of("accountId", a.checking(), "kind", "POSSIBLE_MATCH"));

        assertThat(queue.get("items").size()).isEqualTo(1);
        JsonNode item = queue.get("items").get(0);
        assertThat(item.get("outLinkId").asLong()).isEqualTo(outLink);
        assertThat(item.get("inLinkId").asLong()).isEqualTo(inLink);
        assertThat(item.get("out").get("id").asLong()).isEqualTo(out);
        assertThat(item.get("in").get("id").asLong()).isEqualTo(in);
        assertThat(item.get("in").get("amount").asString()).isEqualTo("160.00");
        assertThat(item.get("daysApart").asLong()).isEqualTo(5);
        assertThat(item.get("outLinkId").asLong()).isNotEqualTo(farOut);
    }

    @Test
    @Transactional
    void detectionNeverJoinsOneSidedLinks() throws Exception {
        Accounts a = sqlAccounts();
        long outLink = rows.userLink(rows.txn(a.checking(), "-160.00", DAY), null, "UNMATCHED");
        long inLink = rows.userLink(null, rows.txn(a.savings(), "160.00", DAY), "UNMATCHED");

        detect();

        assertThat(jdbc.queryForList("SELECT status FROM transfer_link WHERE id IN (?, ?)", String.class, outLink, inLink))
                .containsExactly("UNMATCHED", "UNMATCHED");
        assertThat(queue(Map.of("accountId", a.checking())).get("counts").get("possibleMatches").asInt()).isEqualTo(1);
    }

    @Test
    @Transactional
    void joiningReplacesTheTwoOneSidedLinksWithOneConfirmedLink() throws Exception {
        Accounts a = sqlAccounts();
        long out = rows.txn(a.checking(), "-160.00", DAY);
        long in = rows.txn(a.savings(), "160.00", DAY.plusDays(2));
        long outLink = rows.userLink(out, null, "UNMATCHED");
        long inLink = rows.userLink(null, in, "UNMATCHED");

        ResultActions joined = join("/api/transfers/join", outLink, inLink)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("MATCHED"))
                .andExpect(jsonPath("$.method").value("USER"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.daysApart").value(2))
                .andExpect(jsonPath("$.out.id").value(out))
                .andExpect(jsonPath("$.in.id").value(in));
        long id = json(joined).get("id").asLong();
        joined.andExpect(header().string("Location", endsWith("/api/transfers/" + id)));

        assertThat(support.count("SELECT count(*) FROM transfer_link WHERE id IN (?, ?)", outLink, inLink)).isZero();
        assertThat(rows.isTransfer(out)).isTrue();
        assertThat(rows.isTransfer(in)).isTrue();
        assertThat(queue(Map.of("accountId", a.checking())).get("items").size()).isZero();
        mockMvc.perform(get("/api/transfers/{id}", id)).andExpect(status().isOk());
    }

    @Test
    @Transactional
    void joiningOneOfSeveralPossibleMatchesRemovesTheOthers() throws Exception {
        Accounts a = sqlAccounts();
        long outLink = rows.userLink(rows.txn(a.checking(), "-160.00", DAY), null, "UNMATCHED");
        long savingsLink = rows.userLink(null, rows.txn(a.savings(), "160.00", DAY), "UNMATCHED");
        long cardLink = rows.userLink(null, rows.txn(a.card(), "160.00", DAY, "PAYMENT"), "UNMATCHED");
        assertThat(queue(Map.of("accountId", a.checking())).get("counts").get("possibleMatches").asInt()).isEqualTo(2);

        join("/api/transfers/join", outLink, cardLink).andExpect(status().isCreated());

        assertThat(queue(Map.of("accountId", a.checking())).get("items").size()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM transfer_link WHERE id = ?", String.class, savingsLink))
                .isEqualTo("UNMATCHED");
    }

    @Test
    @Transactional
    void joinChecksTheLinksThenThePairRules() throws Exception {
        Accounts a = sqlAccounts();
        long outLink = rows.userLink(rows.txn(a.checking(), "-160.00", DAY), null, "UNMATCHED");
        long inLink = rows.userLink(null, rows.txn(a.savings(), "160.00", DAY), "UNMATCHED");
        long unequalIn = rows.userLink(null, rows.txn(a.savings(), "160.01", DAY), "UNMATCHED");
        long sameAccountIn = rows.userLink(null, rows.txn(a.checking(), "160.00", DAY), "UNMATCHED");
        long matched = rows.userLink(rows.txn(a.checking(), "-90.00", DAY), rows.txn(a.savings(), "90.00", DAY),
                "MATCHED");

        for (String path : List.of("/api/transfers/join", "/api/transfers/join/dismiss")) {
            join(path, Long.MAX_VALUE, inLink).andExpect(status().isNotFound());
            join(path, outLink, Long.MAX_VALUE).andExpect(status().isNotFound());
            conflict(join(path, inLink, inLink), inLink, "Transfer link " + inLink
                    + " is not a one-sided link with an out side");
            conflict(join(path, outLink, outLink), outLink, "Transfer link " + outLink
                    + " is not a one-sided link with an in side");
            conflict(join(path, matched, inLink), matched, "Transfer link " + matched
                    + " is not a one-sided link with an out side");
            invalid(join(path, outLink, unequalIn), "The two amounts must be equal and opposite");
            invalid(join(path, outLink, sameAccountIn), "The two transactions must be in different accounts");
            mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"outLinkId\": " + outLink + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid request"))
                    .andExpect(jsonPath("$.errors[0].field").value("inLinkId"));
        }
        assertThat(support.count("SELECT count(*) FROM transfer_link WHERE id IN (?, ?, ?, ?, ?)",
                outLink, inLink, unequalIn, sameAccountIn, matched)).isEqualTo(5);
        assertThat(support.count("SELECT count(*) FROM transfer_rejection r JOIN txn t ON t.id = r.out_txn_id "
                + "WHERE t.account_id = ?", a.checking())).isZero();
    }

    /** Dismissing records the rejected pair; both links stay, and a later manual link still wins (§3). */
    @Test
    @Transactional
    void dismissingAPossibleMatchRecordsTheRejection() throws Exception {
        Accounts a = sqlAccounts();
        long out = rows.txn(a.checking(), "-160.00", DAY);
        long in = rows.txn(a.savings(), "160.00", DAY);
        long outLink = rows.userLink(out, null, "UNMATCHED");
        long inLink = rows.userLink(null, in, "UNMATCHED");

        join("/api/transfers/join/dismiss", outLink, inLink).andExpect(status().isNoContent());
        join("/api/transfers/join/dismiss", outLink, inLink).andExpect(status().isNoContent()); // idempotent

        assertThat(support.count("SELECT count(*) FROM transfer_rejection WHERE out_txn_id = ? AND in_txn_id = ?",
                out, in)).isOne();
        assertThat(support.count("SELECT count(*) FROM transfer_link WHERE id IN (?, ?)", outLink, inLink)).isEqualTo(2);
        assertThat(queue(Map.of("accountId", a.checking())).get("items").size()).isZero();

        join("/api/transfers/join", outLink, inLink).andExpect(status().isCreated()); // the explicit join still wins
        assertThat(support.count("SELECT count(*) FROM transfer_rejection WHERE out_txn_id = ?", out)).isZero();
    }

    // ------------------------------------------------------------------ suggested one-sided transfers (CAT-08)

    @Test
    @Transactional
    void aTransferCategoryWithoutALinkIsSuggested() throws Exception {
        Accounts a = sqlAccounts();
        long byRule = rows.txn(a.checking(), "-500.00", DAY);
        ruleTransfer(byRule);
        long byUser = rows.txn(a.checking(), "250.00", DAY);
        categorize(byUser, "Transfer", "USER");
        jdbc.update("UPDATE txn SET categorized_at = now() - interval '1 day' WHERE id = ?", byUser);
        long expense = rows.txn(a.checking(), "-40.00", DAY);
        categorize(expense, "Groceries", "USER");
        rows.txn(a.checking(), "-41.00", DAY); // uncategorized
        long seed = jdbc.queryForObject("SELECT category_rule_id FROM txn WHERE id = ?", Long.class, byRule);

        JsonNode queue = queue(Map.of("accountId", a.checking()));

        assertThat(queue.get("counts")).isEqualTo(counts(0, 0, 0, 0, 0, 2));
        assertThat(kinds(queue)).containsOnly("SUGGESTED_ONE_SIDED_TRANSFER");
        JsonNode newest = queue.get("items").get(0);
        assertThat(newest.get("key").asString()).isEqualTo("txn-" + byRule);
        assertThat(newest.get("txn").get("id").asLong()).isEqualTo(byRule);
        assertThat(newest.get("txn").get("accountId").asLong()).isEqualTo(a.checking());
        assertThat(newest.get("txn").get("amount").asString()).isEqualTo("-500.00");
        assertThat(newest.get("categorySource").asString()).isEqualTo("RULE");
        assertThat(newest.get("ruleId").asLong()).isEqualTo(seed);
        assertThat(instant(newest.get("since"))).isEqualTo(jdbc.queryForObject(
                "SELECT categorized_at FROM txn WHERE id = ?", OffsetDateTime.class, byRule).toInstant());
        JsonNode older = queue.get("items").get(1);
        assertThat(older.get("key").asString()).isEqualTo("txn-" + byUser);
        assertThat(older.get("categorySource").asString()).isEqualTo("USER");
        assertThat(older.get("ruleId").isNull()).isTrue();

        assertThat(suggestedIds(a.checking())).containsExactly(byRule, byUser);
        assertThat(queue(Map.of("accountId", a.savings())).get("counts")).isEqualTo(counts(0, 0, 0, 0, 0, 0));
        JsonNode statements = queue(Map.of("accountId", a.checking(), "kind", "STATEMENT"));
        assertThat(statements.get("items").size()).isZero();
        assertThat(statements.get("counts").get("suggestedOneSidedTransfers").asInt()).as("counts ignore kind")
                .isEqualTo(2);
    }

    /** Linked, excluded, in a candidate group or competing for a link: not suggested (listed there instead). */
    @Test
    @Transactional
    void linkedExcludedGroupedAndCompetingTxnsAreNotSuggested() throws Exception {
        Accounts a = sqlAccounts();
        List<Long> transferKind = new ArrayList<>();
        transferKind.add(rows.txn(a.checking(), "-70.00", DAY)); // one-sided link
        rows.userLink(transferKind.getLast(), null, "UNMATCHED");
        transferKind.add(rows.txn(a.checking(), "-80.00", DAY)); // not a transfer
        rows.exclude(transferKind.getLast());
        transferKind.add(rows.txn(a.checking(), "-200.00", DAY)); // a group of three
        transferKind.add(rows.txn(a.savings(), "200.00", DAY));
        transferKind.add(rows.txn(a.card(), "200.00", DAY.plusDays(1), "PAYMENT"));
        long linkOut = rows.txn(a.checking(), "-300.00", DAY); // an AUTO link and a competitor
        transferKind.add(linkOut);
        long linkIn = rows.txn(a.savings(), "300.00", DAY);
        transferKind.add(linkIn);
        rows.autoLink(linkOut, linkIn);
        transferKind.add(rows.txn(a.card(), "300.00", DAY.plusDays(2), "PAYMENT"));
        transferKind.forEach(txn -> categorize(txn, "Transfer", "RULE"));

        for (long account : List.of(a.checking(), a.savings(), a.card())) {
            assertThat(suggestedIds(account)).as("account " + account).isEmpty();
        }
        assertThat(queue(Map.of("accountId", a.card())).get("counts")).isEqualTo(counts(0, 0, 1, 1, 0, 0));
    }

    /** Accepting (one-sided, either sign) is remembered: later detection and rules passes don't bring it back. */
    @Test
    @Transactional
    void acceptingAsOneSidedIsRemembered() throws Exception {
        Accounts a = sqlAccounts();
        long out = rows.txn(a.checking(), "-500.00", DAY);
        long in = rows.txn(a.savings(), "750.00", DAY);
        ruleTransfer(out);
        ruleTransfer(in);
        assertThat(suggestedIds(a.checking())).containsExactly(out);

        createTransfer("{\"outTxnId\": " + out + "}");
        createTransfer("{\"inTxnId\": " + in + "}");
        detect();
        categorization.categorizeTxn(out);
        categorization.categorizeTxn(in);

        assertThat(suggestedIds(a.checking())).isEmpty();
        assertThat(suggestedIds(a.savings())).isEmpty();
        assertThat(rows.isTransfer(out)).isTrue();
    }

    /**
     * Dismissing (not a transfer) is remembered: the RULE Transfer category is cleared and the txn waits for the
     * user's category (step 9d). Undoing the exclusion and the next rules pass list it again.
     */
    @Test
    @Transactional
    void dismissingAsNotATransferIsRemembered() throws Exception {
        Accounts a = sqlAccounts();
        long out = rows.txn(a.checking(), "-500.00", DAY);
        ruleTransfer(out);

        mockMvc.perform(post("/api/transactions/{id}/not-transfer", out)).andExpect(status().isOk());
        detect();
        categorization.categorizeTxn(out);

        assertThat(suggestedIds(a.checking())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT category_id FROM txn WHERE id = ?", Long.class, out)).isNull();
        assertThat(kinds(queue(Map.of("accountId", a.checking())))).containsExactly("NOT_TRANSFER_UNCATEGORIZED");

        mockMvc.perform(delete("/api/transactions/{id}/not-transfer", out)).andExpect(status().isNoContent());
        categorization.categorizeTxn(out);
        assertThat(suggestedIds(a.checking())).containsExactly(out);
    }

    @Test
    @Transactional
    void linkingByHandOrCorrectingTheCategoryRemovesIt() throws Exception {
        Accounts a = sqlAccounts();
        long linked = rows.txn(a.checking(), "-500.00", DAY);
        long counterpart = rows.txn(a.savings(), "500.00", DAY.plusDays(9)); // outside the window
        long corrected = rows.txn(a.checking(), "-60.00", DAY);
        ruleTransfer(linked);
        ruleTransfer(corrected);
        assertThat(suggestedIds(a.checking())).containsExactlyInAnyOrder(linked, corrected);

        createTransfer("{\"outTxnId\": " + linked + ", \"inTxnId\": " + counterpart + "}");
        mockMvc.perform(put("/api/transactions/{id}/category", corrected).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\": " + jdbc.queryForObject("SELECT id FROM category WHERE name = 'Groceries'",
                                Long.class) + "}"))
                .andExpect(status().isOk());

        assertThat(suggestedIds(a.checking())).isEmpty();
        assertThat(queue(Map.of("accountId", a.checking())).get("items").size()).isZero();
    }

    // ------------------------------------------------------------------ the duplicate-account story (§7)

    /**
     * The checking statement imported into a second account by mistake: the card payment has two out
     * candidates, so it is a review group. Deleting the wrong copy removes the group and links the payment.
     */
    @Test
    void deletingTheWrongCopyResolvesTheGroup() throws Exception {
        long right = support.account(AccountType.CHECKING, "1001");
        // Same type and last four, so the file is accepted; another institution, since V12 allows one per bank.
        long wrong = support.account(AccountType.CHECKING, "1001", "Test 1001", "Other Bank");
        long card = support.account(AccountType.CREDIT_CARD, "2001");
        saveSuggestedMapping(right, CHECKING_CSV);
        importOk(right, CHECKING_CSV, Map.of("periodStart", "2026-04-01", "periodEnd", "2026-04-30"));
        long wrongStatement = importOk(wrong, OFX_APRIL, Map.of()).get("statementId").asLong();
        importOk(card, APEX_QFX, Map.of());

        JsonNode queue = queue(Map.of("accountId", card, "kind", "TRANSFER_CANDIDATES"));
        assertThat(queue.get("items").size()).as("each card payment has two out candidates").isPositive();
        for (JsonNode group : queue.get("items")) {
            assertThat(group.get("pairs").size()).isEqualTo(2);
            Set<Long> accounts = new HashSet<>();
            group.get("txns").forEach(txn -> accounts.add(txn.get("accountId").asLong()));
            assertThat(accounts).containsExactlyInAnyOrder(right, wrong, card);
        }

        mockMvc.perform(delete("/api/statements/{id}", wrongStatement)).andExpect(status().isOk());

        assertThat(queue(Map.of("accountId", card)).get("counts")).isEqualTo(counts(0, 0, 0, 0, 0, 0));
        assertThat(support.count("SELECT count(*) FROM transfer_link l JOIN txn t ON t.id = l.in_txn_id "
                + "WHERE t.account_id = ?", card)).isPositive();
    }

    // ------------------------------------------------------------------ helpers

    private record Accounts(long checking, long savings, long card) {
    }

    /** Three accounts in plain SQL, for tests that roll back. */
    private Accounts sqlAccounts() {
        return new Accounts(rows.account("CHECKING", "2001"), rows.account("SAVINGS", "2002"),
                rows.account("CREDIT_CARD", "2003"));
    }

    private long sqlUpload(long accountId) {
        return jdbc.queryForObject("INSERT INTO unparsed_upload (account_id, file_sha256, file_name, reason) "
                + "VALUES (?, ?, 'card.pdf', 'UNRECOGNIZED_LAYOUT') RETURNING id", Long.class, accountId, "c".repeat(64));
    }

    private JsonNode queue(Map<String, Object> params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/review-queue");
        params.forEach((name, value) -> request.param(name, String.valueOf(value)));
        return json(mockMvc.perform(request).andExpect(status().isOk()));
    }

    private static List<String> kinds(JsonNode queue) {
        List<String> kinds = new ArrayList<>();
        queue.get("items").forEach(item -> kinds.add(item.get("kind").asString()));
        return kinds;
    }

    private static JsonNode item(JsonNode queue, String kind) {
        for (JsonNode item : queue.get("items")) {
            if (item.get("kind").asString().equals(kind)) {
                return item;
            }
        }
        throw new AssertionError("no " + kind + " item in " + queue);
    }

    private JsonNode counts(int statements, int uploads, int groups, int competing, int possibleMatches) {
        return counts(statements, uploads, groups, competing, possibleMatches, 0);
    }

    private JsonNode counts(int statements, int uploads, int groups, int competing, int possibleMatches,
                            int suggested) {
        return jsonMapper.valueToTree(Map.of("statements", statements, "unparsedUploads", uploads,
                "transferCandidates", groups, "competingCandidates", competing, "possibleMatches", possibleMatches,
                "suggestedOneSidedTransfers", suggested, "aiCategoryReviews", 0, "notTransferUncategorized", 0));
    }

    /** Gives the txn a description a seeded Transfer rule matches, and runs the rules for it. */
    private void ruleTransfer(long txnId) {
        jdbc.update("UPDATE txn SET raw_description = 'ONLINE TRANSFER TO SAV 5521' WHERE id = ?", txnId);
        categorization.categorizeTxn(txnId);
    }

    /** Sets a category as the user or the AI would, bypassing the API. */
    private void categorize(long txnId, String category, String source) {
        jdbc.update("""
                UPDATE txn SET category_id = (SELECT id FROM category WHERE name = ?), category_source = ?,
                               category_rule_id = NULL, categorized_at = now()
                WHERE id = ?""", category, source, txnId);
    }

    private List<Long> suggestedIds(long accountId) throws Exception {
        List<Long> ids = new ArrayList<>();
        queue(Map.of("accountId", accountId, "kind", "SUGGESTED_ONE_SIDED_TRANSFER")).get("items")
                .forEach(item -> ids.add(item.get("txn").get("id").asLong()));
        return ids;
    }

    private static List<Long> ids(JsonNode txns) {
        List<Long> ids = new ArrayList<>();
        txns.forEach(txn -> ids.add(txn.get("id").asLong()));
        return ids;
    }

    /** The JSON of {@code pairs}, from {@code outTxnId, inTxnId, daysApart} triples. */
    private static String pairs(long... triples) {
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < triples.length; i += 3) {
            pairs.add("{\"outTxnId\":" + triples[i] + ",\"inTxnId\":" + triples[i + 1] + ",\"daysApart\":"
                    + triples[i + 2] + "}");
        }
        return "[" + String.join(",", pairs) + "]";
    }

    private Instant createdAt(long txnId) {
        return jdbc.queryForObject("SELECT created_at FROM txn WHERE id = ?", OffsetDateTime.class, txnId).toInstant();
    }

    private static Instant instant(JsonNode timestamp) {
        return OffsetDateTime.parse(timestamp.asString()).toInstant();
    }

    private void detect() throws Exception {
        mockMvc.perform(post("/api/transfers/detect")).andExpect(status().isOk());
    }

    private long linkOf(long txnId) {
        return jdbc.queryForObject("SELECT id FROM transfer_link WHERE out_txn_id = ? OR in_txn_id = ?", Long.class,
                txnId, txnId);
    }

    private void createTransfer(String body) throws Exception {
        mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private ResultActions join(String path, long outLinkId, long inLinkId) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outLinkId\": " + outLinkId + ", \"inLinkId\": " + inLinkId + "}"));
    }

    private static void conflict(ResultActions result, long linkId, String detail) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "transfer-conflict"))
                .andExpect(jsonPath("$.linkId").value(linkId))
                .andExpect(jsonPath("$.detail").value(detail));
    }

    private static void invalid(ResultActions result, String detail) throws Exception {
        result.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEM + "transfer-invalid"))
                .andExpect(jsonPath("$.detail").value(detail));
    }

    private void saveSuggestedMapping(long accountId, String sampleFile) throws Exception {
        String fileName = sampleFile.substring(sampleFile.lastIndexOf('/') + 1);
        JsonNode preview = json(support.preview(accountId, fileName, ImportTestSupport.sample(sampleFile))
                .andExpect(status().isOk()));
        support.putMapping(accountId, preview.get("suggestedMapping").get("mapping").toString())
                .andExpect(status().isCreated());
    }

    private long upload(long accountId) throws Exception {
        return json(support.upload(accountId, "card.pdf", ImportTestSupport.sample(UNRECOGNIZED))
                .andExpect(status().isAccepted())).get("id").asLong();
    }

    private JsonNode importOk(long accountId, String sampleFile, Map<String, String> params) throws Exception {
        String fileName = sampleFile.substring(sampleFile.lastIndexOf('/') + 1);
        return json(support.upload(accountId, fileName, ImportTestSupport.sample(sampleFile), params)
                .andExpect(status().isCreated()));
    }

    private JsonNode review(long statementId) throws Exception {
        return json(mockMvc.perform(post("/api/statements/{id}/review", statementId)).andExpect(status().isOk()));
    }

    private JsonNode statement(long id) throws Exception {
        return json(mockMvc.perform(get("/api/statements/{id}", id)).andExpect(status().isOk()));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}

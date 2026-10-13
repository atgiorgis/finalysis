package com.finalysis.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The review actions (ING-07, ING-13; docs/plans/phase-3c.md §4.2, §4.3): mark a NEEDS_REVIEW
 * statement reviewed, the late-arrival re-queue, and reading and dismissing unparsed uploads. Step 6
 * adds the queue itself ({@code GET /api/review-queue}). Imports commit for real; accounts created
 * here are deleted after each test.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ReviewQueueApiTest {

    private static final String PROBLEM = "urn:finalysis:problem:";
    private static final String OFX_APRIL = "ofx/northfield-checking_2026-04.ofx";
    private static final String OFX_MAY = "ofx/northfield-checking_2026-05.ofx";
    private static final String LEDGERBAL_MISMATCH = "edge-cases/ofx-ledgerbal-mismatch.ofx"; // a May statement
    private static final String UNRECOGNIZED = "pdf/edge-cases/pdf-unrecognized-layout.pdf";

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final ImportTestSupport support;

    ReviewQueueApiTest(MockMvc mockMvc, JsonMapper jsonMapper, AccountRepository accounts, JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
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

    // ------------------------------------------------------------------ helpers

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

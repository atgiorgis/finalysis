package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reconciliation through the import API (ING-06, ING-10): balance column, request balances, OFX
 * continuity and late arrivals, status precedence, and the statement list. No test transaction;
 * accounts created here are deleted after each test.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ReconciliationImportTest {

    private static final String UNVERIFIED = Reconciliation.UNVERIFIED_WARNING;
    private static final String OFX_APRIL = "ofx/northfield-checking_2026-04.ofx";
    private static final String OFX_MAY = "ofx/northfield-checking_2026-05.ofx";
    private static final String OFX_JUNE = "ofx/northfield-checking_2026-06.ofx";
    private static final String CSV_APRIL = "statements/northfield-checking_2026-04.csv";

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final ImportTestSupport support;

    ReconciliationImportTest(MockMvc mockMvc, JsonMapper jsonMapper, AccountRepository accounts,
                             JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    // ------------------------------------------------------------------ CSV

    @Test
    void cardCsvWithoutBalancesIsUnverified() throws Exception {
        for (String file : List.of("statements/apex-visa_2026-05.csv", "statements/summit-card_2026-05.csv")) {
            long card = support.account(AccountType.CREDIT_CARD, "2001");
            saveSuggestedMapping(card, file);

            JsonNode summary = importOk(card, file, Map.of());

            assertThat(summary.get("status").asString()).as(file).isEqualTo("IMPORTED");
            assertThat(summary.get("balanceCheck").asString()).isEqualTo("UNVERIFIED");
            assertThat(strings(summary.get("warnings"))).containsExactly(UNVERIFIED);
            assertThat(summary.get("openingBalance").isNull()).isTrue();
            assertThat(summary.get("closingBalance").isNull()).isTrue();
        }
    }

    @Test
    void brokenSummitStatementWithManifestBalancesNeedsReview() throws Exception {
        long summit = support.account(AccountType.CREDIT_CARD, "3001");
        saveSuggestedMapping(summit, "statements/summit-card_2026-05.csv");
        String[] june = manifest("broken/summit-card_2026-06.csv");
        // The broken file lacks one row of the real statement: the difference is that row's amount.
        BigDecimal missing = ImportTestSupport.sum(ImportTestSupport.answerKey("statements/summit-card_2026-06.csv"))
                .subtract(sumOfFile("broken/summit-card_2026-06.csv"));

        JsonNode summary = importOk(summit, "broken/summit-card_2026-06.csv",
                Map.of("openingBalance", june[4], "closingBalance", june[5]));

        assertThat(summary.get("status").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(summary.get("balanceCheck").asString()).isEqualTo("FAILED");
        BigDecimal expected = new BigDecimal(june[5]).subtract(missing);
        assertThat(strings(summary.get("warnings"))).containsExactly("reconciliation failed: request balances: "
                + "openingBalance + rows = closingBalance: expected " + expected.toPlainString() + ", actual " + june[5]
                + ", difference " + missing.toPlainString());
    }

    @Test
    void newestFirstCsvWithABalanceColumnReconciles() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        saveSuggestedMapping(checking, CSV_APRIL);
        List<String> lines = new ArrayList<>(sampleLines(CSV_APRIL));
        Collections.reverse(lines.subList(1, lines.size()));

        JsonNode summary = importOk(checking, "newest-first.csv", bytes(lines), Map.of());

        String[] april = manifest(CSV_APRIL);
        assertThat(summary.get("status").asString()).isEqualTo("RECONCILED");
        assertThat(summary.get("openingBalance").asString()).isEqualTo(april[4]);
        assertThat(summary.get("closingBalance").asString()).isEqualTo(april[5]);
    }

    @Test
    void aBrokenRunningBalanceNamesItsLine() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        saveSuggestedMapping(checking, CSV_APRIL);
        List<String> lines = new ArrayList<>(sampleLines(CSV_APRIL));
        String row = lines.get(4); // line 5
        String balance = row.substring(row.lastIndexOf(',') + 1);
        BigDecimal wrong = new BigDecimal(balance).add(new BigDecimal("1.00"));
        lines.set(4, row.substring(0, row.lastIndexOf(',') + 1) + wrong.toPlainString());

        JsonNode summary = importOk(checking, "typo.csv", bytes(lines), Map.of());

        // Pairwise: line 5 disagrees with line 4, and line 6 with line 5's wrong balance.
        assertThat(summary.get("status").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(strings(summary.get("warnings"))).containsExactly(
                "reconciliation failed: balance column, line 5: expected " + balance + ", actual "
                        + wrong.toPlainString() + ", difference 1.00",
                "reconciliation failed: balance column, line 6: expected " + nextBalance(lines, 5, wrong) + ", actual "
                        + lines.get(5).substring(lines.get(5).lastIndexOf(',') + 1) + ", difference -1.00");
    }

    @Test
    void anOverlapWithNewRowsNeedsReviewEvenWhenBalancesPass() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        saveSuggestedMapping(checking, CSV_APRIL);
        List<String> lines = sampleLines(CSV_APRIL);
        long partial = importOk(checking, "partial.csv", bytes(lines.subList(0, lines.size() - 1)), Map.of())
                .get("statementId").asLong();

        JsonNode full = importOk(checking, "april.csv", ImportTestSupport.sample(CSV_APRIL), Map.of());

        assertThat(full.get("inserted").asInt()).isEqualTo(1);
        assertThat(full.get("balanceCheck").asString()).isEqualTo("PASSED");
        assertThat(full.get("status").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(strings(full.get("warnings"))).containsExactly("period overlaps statement " + partial);
    }

    // ------------------------------------------------------------------ OFX continuity

    @Test
    void ledgerBalanceThatDoesNotContinueTheAprilStatementNeedsReview() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long april = importOk(checking, OFX_APRIL, Map.of()).get("statementId").asLong();

        JsonNode may = importOk(checking, "edge-cases/ofx-ledgerbal-mismatch.ofx", Map.of());

        String continuity = "reconciliation failed: continuity: statement " + april
                + "'s closingBalance + rows = closingBalance: expected 8236.77, actual 8246.77, difference 10.00";
        assertThat(may.get("status").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(may.get("openingBalance").isNull()).isTrue();
        assertThat(strings(may.get("warnings"))).containsExactly(continuity);
        mockMvc.perform(get("/api/statements/{id}", may.get("statementId").asLong()))
                .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.balanceCheck").value("FAILED"))
                .andExpect(jsonPath("$.closingBalance").value("8246.77"))
                .andExpect(jsonPath("$.warnings[0]").value(continuity));
    }

    @Test
    void mayImportedBeforeAprilReconcilesWhenAprilArrives() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long may = importOk(checking, OFX_MAY, Map.of()).get("statementId").asLong();
        assertThat(statement(may).get("status").asString()).isEqualTo("IMPORTED");
        assertThat(strings(statement(may).get("warnings"))).containsExactly(UNVERIFIED);

        JsonNode april = importOk(checking, OFX_APRIL, Map.of());

        assertThat(april.get("status").asString()).isEqualTo("IMPORTED"); // nothing before April
        JsonNode mayNow = statement(may);
        assertThat(mayNow.get("status").asString()).isEqualTo("RECONCILED");
        assertThat(mayNow.get("balanceCheck").asString()).isEqualTo("PASSED");
        assertThat(mayNow.get("openingBalance").asString()).isEqualTo(manifest(CSV_APRIL)[5]);
        assertThat(mayNow.get("warnings")).isEmpty();
    }

    @Test
    void aLateArrivalCanAlsoRevealAMismatch() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long may = importOk(checking, "edge-cases/ofx-ledgerbal-mismatch.ofx", Map.of()).get("statementId").asLong();

        importOk(checking, OFX_APRIL, Map.of());

        JsonNode mayNow = statement(may);
        assertThat(mayNow.get("status").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(mayNow.get("balanceCheck").asString()).isEqualTo("FAILED");
        assertThat(strings(mayNow.get("warnings"))).singleElement().asString().endsWith("difference 10.00");
    }

    @Test
    void aLateArrivalKeepsOtherReviewReasons() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        // Rows after the 20th fall outside this period: NEEDS_REVIEW, and unverified.
        long may = importOk(checking, OFX_MAY, Map.of("periodEnd", "2026-05-20")).get("statementId").asLong();

        importOk(checking, OFX_APRIL, Map.of());

        JsonNode mayNow = statement(may);
        assertThat(mayNow.get("status").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(mayNow.get("balanceCheck").asString()).isEqualTo("PASSED");
        assertThat(strings(mayNow.get("warnings"))).singleElement().asString().contains("outside the statement period");
    }

    // ------------------------------------------------------------------ statement list

    @Test
    void statementListIsNewestPeriodFirstAndFiltersByStatus() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        long june = importOk(checking, OFX_JUNE, Map.of()).get("statementId").asLong();
        long april = importOk(checking, OFX_APRIL, Map.of()).get("statementId").asLong();
        long may = importOk(checking, OFX_MAY, Map.of()).get("statementId").asLong(); // June now reconciles too
        long contradicting = importOk(checking, "edge-cases/ofx-trntype-contradicts-sign.ofx", Map.of())
                .get("statementId").asLong(); // an April re-download with new rows: NEEDS_REVIEW

        JsonNode all = list(checking, null);

        assertThat(ids(all)).containsExactly(june, may, contradicting, april);
        JsonNode first = all.get(0);
        assertThat(first.get("parser").asString()).isEqualTo("ofx");
        assertThat(first.get("periodStart").asString()).isEqualTo("2026-06-01");
        assertThat(first.get("periodEnd").asString()).isEqualTo("2026-06-30");
        assertThat(first.get("openingBalance").asString()).isEqualTo(manifest("statements/northfield-checking_2026-05.csv")[5]);
        assertThat(first.get("closingBalance").asString()).isEqualTo(manifest("statements/northfield-checking_2026-06.csv")[5]);
        assertThat(first.get("status").asString()).isEqualTo("RECONCILED");
        assertThat(first.get("balanceCheck").asString()).isEqualTo("PASSED");
        assertThat(first.get("parsedCount").asInt()).isEqualTo(first.get("transactionCount").asInt()).isPositive();
        assertThat(first.get("warningCount").asInt()).isZero();
        assertThat(first.get("importedAt").asString()).isNotEmpty();
        assertThat(first.has("warnings")).isFalse();
        assertThat(all.get(2).get("warningCount").asInt()).isPositive();

        assertThat(ids(list(checking, "RECONCILED"))).containsExactly(june, may);
        assertThat(ids(list(checking, "NEEDS_REVIEW"))).containsExactly(contradicting);
        assertThat(ids(list(checking, "IMPORTED"))).containsExactly(april);
        mockMvc.perform(get("/api/accounts/{id}/statements", checking).param("status", "DONE"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/accounts/{id}/statements", 999_999_999)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private void saveSuggestedMapping(long accountId, String sampleFile) throws Exception {
        JsonNode preview = json(support.preview(accountId, "x.csv", ImportTestSupport.sample(sampleFile))
                .andExpect(status().isOk()).andReturn());
        support.putMapping(accountId, preview.get("suggestedMapping").get("mapping").toString())
                .andExpect(status().isCreated());
    }

    private JsonNode importOk(long accountId, String sampleFile, Map<String, String> params) throws Exception {
        return importOk(accountId, Path.of(sampleFile).getFileName().toString(), ImportTestSupport.sample(sampleFile), params);
    }

    private JsonNode importOk(long accountId, String fileName, byte[] bytes, Map<String, String> params) throws Exception {
        return json(support.upload(accountId, fileName, bytes, params).andExpect(status().isCreated()).andReturn());
    }

    private JsonNode statement(long id) throws Exception {
        return json(mockMvc.perform(get("/api/statements/{id}", id)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode list(long accountId, String statusFilter) throws Exception {
        var request = get("/api/accounts/{id}/statements", accountId);
        if (statusFilter != null) {
            request.param("status", statusFilter);
        }
        return json(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    private static List<Long> ids(JsonNode items) {
        return items.valueStream().map(item -> item.get("id").asLong()).toList();
    }

    private JsonNode json(MvcResult result) throws IOException {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }

    /** The manifest row of a statement file: [file, account, start, end, opening, closing]. */
    private static String[] manifest(String statementFile) {
        try {
            return Files.readAllLines(ImportTestSupport.SAMPLE_DATA.resolve("statements-manifest.csv")).stream()
                    .map(line -> line.split(",", -1)).filter(c -> c[0].equals(statementFile)).findFirst().orElseThrow();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> sampleLines(String file) {
        return new String(ImportTestSupport.sample(file), StandardCharsets.UTF_8).lines().toList();
    }

    private static byte[] bytes(List<String> lines) {
        return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Summit layout: the amount is the last column. */
    private static BigDecimal sumOfFile(String file) {
        return sampleLines(file).stream().skip(1).map(line -> new BigDecimal(line.substring(line.lastIndexOf(',') + 1)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Northfield layout: what line {@code index + 1}'s balance should be after a wrong balance on the line before. */
    private static String nextBalance(List<String> lines, int index, BigDecimal previous) {
        String[] cells = lines.get(index).split(",", -1);
        BigDecimal amount = cells[2].isEmpty() ? new BigDecimal(cells[3]) : new BigDecimal(cells[2]).negate();
        return previous.add(amount).toPlainString();
    }
}

package com.finalysis.analytics;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountType;
import com.finalysis.support.ImportTestSupport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Phase 5 fixture ({@code sample-data/analytics/}, docs/plans/phase-5.md §9.1), imported for real. Statements go
 * through the import API with each layout's suggested CSV mapping, transfer detection links the card payments, and
 * then every row's category state is set from {@code categories.csv}. Analytics tests so depend on the counting
 * rules, never on how well rules or the AI categorize.
 *
 * <p>Imports commit, so the caller deletes the accounts afterwards ({@link ImportTestSupport#deleteCreatedAccounts()}).
 */
final class AnalyticsFixture {

    static final Path DIR = ImportTestSupport.SAMPLE_DATA.resolve("analytics");
    static final String CHECKING = "fixture-checking";
    static final String CARD = "fixture-card";

    /** One manifest row: a statement file and how it is imported. */
    record Statement(String file, String account, LocalDate periodStart, LocalDate periodEnd, boolean sendBalances,
                     BigDecimal openingBalance, BigDecimal closingBalance, String expectedBalanceCheck) {
    }

    /** One {@code categories.csv} row: the category state a fixture txn gets, and the bucket it must land in. */
    record Entry(String file, int line, LocalDate date, BigDecimal amount, String description, String category,
                 String source, String aiReviewReason, boolean transferExcluded, TxnBucket expectedBucket) {

        String ref() {
            return file + ":" + line;
        }
    }

    /** What an import of the fixture left: account ids by slug, statement ids by file, txn ids by file:line. */
    record Loaded(Map<String, Long> accounts, Map<String, Long> statements, Map<String, Long> txns) {
    }

    private final ImportTestSupport support;
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    AnalyticsFixture(ImportTestSupport support, JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.support = support;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    Loaded load() throws Exception {
        Map<String, Long> accounts = new LinkedHashMap<>();
        accounts.put(CHECKING, account(AccountType.CHECKING, "5101", "Fixture Checking", "fixture-checking_2026-01.csv"));
        accounts.put(CARD, account(AccountType.CREDIT_CARD, "5201", "Fixture Card", "fixture-card_2026-01.csv"));

        Map<String, Long> statements = new LinkedHashMap<>();
        for (Statement statement : manifest()) {
            Map<String, String> params = new HashMap<>();
            params.put("periodStart", statement.periodStart().toString());
            params.put("periodEnd", statement.periodEnd().toString());
            if (statement.sendBalances()) {
                params.put("openingBalance", statement.openingBalance().toPlainString());
                params.put("closingBalance", statement.closingBalance().toPlainString());
            }
            JsonNode summary = json(support.upload(accounts.get(statement.account()), statement.file(),
                    Files.readAllBytes(DIR.resolve(statement.file())), params).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString());
            statements.put(statement.file(), summary.get("statementId").asLong());
        }

        Map<String, Long> txns = new LinkedHashMap<>();
        for (Entry entry : entries()) {
            Long id = jdbc.queryForObject("SELECT id FROM txn WHERE statement_id = ? AND source_line_number = ?",
                    Long.class, statements.get(entry.file()), entry.line());
            txns.put(entry.ref(), id);
            applyCategory(id, entry);
        }
        return new Loaded(accounts, statements, txns);
    }

    /**
     * Sets the row's category state exactly as the key says, replacing whatever the rules pass after the import
     * wrote. One UPDATE, so the AI-provenance and review CHECKs see the final row.
     */
    private void applyCategory(long txnId, Entry entry) {
        Long categoryId = entry.category() == null ? null
                : jdbc.queryForObject("SELECT id FROM category WHERE name = ?", Long.class, entry.category());
        boolean ai = "AI".equals(entry.source());
        jdbc.update("""
                        UPDATE txn
                        SET category_id = ?, category_source = ?, category_rule_id = NULL,
                            categorized_at = CASE WHEN ?::bigint IS NULL THEN NULL ELSE now() END,
                            category_model = ?, category_confidence = ?, category_prompt_version = ?,
                            ai_review_reason = ?, ai_review_at = CASE WHEN ?::text IS NULL THEN NULL ELSE now() END,
                            transfer_excluded = ?
                        WHERE id = ?
                        """,
                categoryId, entry.source(), categoryId,
                ai ? "fixture-model" : null, ai ? new BigDecimal("0.900") : null, ai ? "3" : null,
                entry.aiReviewReason(), entry.aiReviewReason(),
                entry.transferExcluded(), txnId);
    }

    private long account(AccountType type, String lastFour, String name, String firstFile) throws Exception {
        long id = support.account(type, lastFour, name, "Fixture Bank");
        JsonNode preview = json(support.preview(id, firstFile, Files.readAllBytes(DIR.resolve(firstFile)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        support.putMapping(id, preview.get("suggestedMapping").get("mapping").toString())
                .andExpect(status().isCreated());
        return id;
    }

    private JsonNode json(String body) {
        return jsonMapper.readTree(body);
    }

    static List<Statement> manifest() {
        List<Statement> statements = new ArrayList<>();
        for (String[] c : rows("manifest.csv")) {
            statements.add(new Statement(c[0], c[1], LocalDate.parse(c[2]), LocalDate.parse(c[3]),
                    Boolean.parseBoolean(c[4]), new BigDecimal(c[5]), new BigDecimal(c[6]), c[7]));
        }
        return statements;
    }

    static List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        for (String[] c : rows("categories.csv")) {
            entries.add(new Entry(c[0], Integer.parseInt(c[1]), LocalDate.parse(c[2]), new BigDecimal(c[3]), c[4],
                    blankToNull(c[5]), blankToNull(c[6]), blankToNull(c[7]), Boolean.parseBoolean(c[8]),
                    TxnBucket.valueOf(c[9])));
        }
        return entries;
    }

    /** Data rows of a fixture CSV, header skipped. Quoted cells (the notes) may hold commas. */
    static List<String[]> rows(String file) {
        try {
            return Files.readAllLines(DIR.resolve(file)).stream().skip(1).filter(l -> !l.isBlank())
                    .map(AnalyticsFixture::split).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String[] split(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        cells.add(cell.toString());
        return cells.toArray(String[]::new);
    }

    private static String blankToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}

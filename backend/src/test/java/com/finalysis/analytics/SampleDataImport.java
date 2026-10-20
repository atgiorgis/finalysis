package com.finalysis.analytics;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountType;
import com.finalysis.support.ImportTestSupport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The synthetic sample data ({@code sample-data/statements/}, as listed in {@code statements-manifest.csv}) imported
 * for real, the way {@code TransferDetectionTest} does: one account per slug with its layout's suggested CSV mapping,
 * the period of every file, and the balances of the cards, which print none. Rules and detection run as in
 * production. The caller deletes the accounts afterwards. Public for the alert tests (step 5).
 */
public final class SampleDataImport {

    static final List<String> SLUGS = List.of("northfield-checking", "northfield-savings", "apex-visa", "summit-card");

    private final ImportTestSupport support;
    private final JsonMapper jsonMapper;

    public SampleDataImport(ImportTestSupport support, JsonMapper jsonMapper) {
        this.support = support;
        this.jsonMapper = jsonMapper;
    }

    /** Imports every statement; returns account ids by slug. */
    public Map<String, Long> load() throws Exception {
        return load(Set.of());
    }

    /**
     * Imports every statement, with {@code broken/} files in place of the good ones of the same name (the manifest
     * lists both); returns account ids by slug.
     */
    Map<String, Long> load(Set<String> brokenFiles) throws Exception {
        Map<String, Long> accounts = new LinkedHashMap<>();
        for (String slug : SLUGS) {
            accounts.put(slug, account(slug));
        }
        for (String[] entry : manifest()) {
            String name = Path.of(entry[0]).getFileName().toString();
            if (brokenFiles.contains(name)) {
                entry = brokenEntry(name);
            }
            String slug = entry[1];
            Map<String, String> params = new LinkedHashMap<>(Map.of("periodStart", entry[2], "periodEnd", entry[3]));
            if (!slug.startsWith("northfield")) {
                params.putAll(Map.of("openingBalance", entry[4], "closingBalance", entry[5]));
            }
            support.upload(accounts.get(slug), Path.of(entry[0]).getFileName().toString(),
                    ImportTestSupport.sample(entry[0]), params).andExpect(status().isCreated());
        }
        return accounts;
    }

    /**
     * Sets every imported txn's category to the answer key's ({@code expected_category}, as USER), replacing what the
     * rules gave it, so analytics tests on the sample data depend on the counting rules only (§9.3). A row is matched by
     * account, date, raw description and amount; genuine duplicates share all four and their category.
     */
    public void applyAnswerKey(JdbcTemplate jdbc, Map<String, Long> accounts) {
        applyAnswerKey(jdbc, accounts, c -> false);
    }

    /** As {@link #applyAnswerKey(JdbcTemplate, Map)}, skipping the key rows {@code missing} names (a broken file's). */
    void applyAnswerKey(JdbcTemplate jdbc, Map<String, Long> accounts, Predicate<String[]> missing) {
        try {
            for (String[] c : Files.readAllLines(ImportTestSupport.SAMPLE_DATA.resolve("answer-key.csv")).stream()
                    .skip(1).map(line -> line.split(",", -1)).filter(missing.negate()).toList()) {
                int updated = jdbc.update("""
                        UPDATE txn SET category_id = (SELECT id FROM category WHERE name = ?), category_source = 'USER',
                                       category_rule_id = NULL, categorized_at = now()
                        WHERE account_id = ? AND txn_date = ?::date AND raw_description = ? AND amount = ?::numeric
                        """, c[5], accounts.get(c[1]), c[2], c[3], c[4]);
                if (updated == 0) {
                    throw new IllegalStateException("no imported txn for answer-key row " + c[0] + " " + c[2]);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Integer left = jdbc.queryForObject("SELECT count(*) FROM txn WHERE category_id IS NULL", Integer.class);
        if (left != 0) {
            throw new IllegalStateException(left + " imported txns have no answer-key row");
        }
    }

    private long account(String slug) throws Exception {
        String lastFour = switch (slug) {
            case "northfield-checking" -> "1001";
            case "northfield-savings" -> "1002";
            case "apex-visa" -> "2001";
            default -> "3001";
        };
        AccountType type = switch (slug) {
            case "northfield-checking" -> AccountType.CHECKING;
            case "northfield-savings" -> AccountType.SAVINGS;
            default -> AccountType.CREDIT_CARD;
        };
        long id = support.account(type, lastFour);
        String sample = "statements/" + slug + "_2026-04.csv";
        JsonNode preview = jsonMapper.readTree(support.preview(id, Path.of(sample).getFileName().toString(),
                ImportTestSupport.sample(sample)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        support.putMapping(id, preview.get("suggestedMapping").get("mapping").toString())
                .andExpect(status().isCreated());
        return id;
    }

    private static String[] brokenEntry(String name) {
        try {
            return Files.readAllLines(ImportTestSupport.SAMPLE_DATA.resolve("statements-manifest.csv")).stream()
                    .skip(1).map(line -> line.split(",", -1)).filter(c -> c[0].equals("broken/" + name))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("no broken/" + name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The {@code statements/} rows of the manifest: file, slug, period start and end, opening and closing balance. */
    private static List<String[]> manifest() {
        try {
            return Files.readAllLines(ImportTestSupport.SAMPLE_DATA.resolve("statements-manifest.csv")).stream()
                    .skip(1).map(line -> line.split(",", -1)).filter(c -> c[0].startsWith("statements/")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package com.finalysis.chat;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountType;
import com.finalysis.support.ImportTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imports the chat injection fixture ({@code sample-data/chat/injection/}) through the API and applies its key, as
 * {@code AnalyticsFixture} does for the analytics fixture: the one checking account, named "Halden Everyday" at "Halden
 * Bank", last four 7310 (fictional, so redaction has a name, an institution and a last four to catch).
 */
public final class ChatInjectionFixture {

    public static final Path DIR = ImportTestSupport.SAMPLE_DATA.resolve("chat/injection");
    public static final String FILE = "injection-checking_2026-03.csv";
    public static final String NAME = "Halden Everyday";
    public static final String INSTITUTION = "Halden Bank";
    public static final String LAST_FOUR = "7310";

    private final ImportTestSupport support;
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    public ChatInjectionFixture(ImportTestSupport support, JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.support = support;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    /** Imports and categorizes; returns the account id. */
    public long load() throws Exception {
        long account = support.account(AccountType.CHECKING, LAST_FOUR, NAME, INSTITUTION);
        byte[] bytes = Files.readAllBytes(DIR.resolve(FILE));
        JsonNode preview = jsonMapper.readTree(support.preview(account, FILE, bytes).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        support.putMapping(account, preview.get("suggestedMapping").get("mapping").toString())
                .andExpect(status().isCreated());
        CSVRecord manifest = csv(DIR.resolve("manifest.csv")).getFirst();
        JsonNode summary = jsonMapper.readTree(support.upload(account, FILE, bytes, Map.of(
                        "periodStart", manifest.get("period_start"), "periodEnd", manifest.get("period_end")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long statement = summary.get("statementId").asLong();
        for (CSVRecord key : csv(DIR.resolve("categories.csv"))) {
            String category = key.get("category");
            Long categoryId = category.isEmpty() ? null
                    : jdbc.queryForObject("SELECT id FROM category WHERE name = ?", Long.class, category);
            jdbc.update("""
                    UPDATE txn SET category_id = ?, category_source = ?, category_rule_id = NULL,
                        categorized_at = CASE WHEN ?::bigint IS NULL THEN NULL ELSE now() END
                    WHERE statement_id = ? AND source_line_number = ?""",
                    categoryId, categoryId == null ? null : "USER", categoryId, statement,
                    Integer.parseInt(key.get("line")));
        }
        return account;
    }

    /** The hostile descriptions (the key's rows with a note), for the never-sent checks. */
    public static List<String> hostileDescriptions() throws Exception {
        return csv(DIR.resolve("categories.csv")).stream().filter(r -> !r.get("note").isEmpty())
                .map(r -> r.get("description")).toList();
    }

    private static List<CSVRecord> csv(Path file) throws Exception {
        return CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get()
                .parse(Files.newBufferedReader(file)).getRecords();
    }
}

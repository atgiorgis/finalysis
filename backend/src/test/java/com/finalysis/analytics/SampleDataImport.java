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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The synthetic sample data ({@code sample-data/statements/}, as listed in {@code statements-manifest.csv}) imported
 * for real, the way {@code TransferDetectionTest} does: one account per slug with its layout's suggested CSV mapping,
 * the period of every file, and the balances of the cards, which print none. Rules and detection run as in
 * production. The caller deletes the accounts afterwards.
 */
final class SampleDataImport {

    static final List<String> SLUGS = List.of("northfield-checking", "northfield-savings", "apex-visa", "summit-card");

    private final ImportTestSupport support;
    private final JsonMapper jsonMapper;

    SampleDataImport(ImportTestSupport support, JsonMapper jsonMapper) {
        this.support = support;
        this.jsonMapper = jsonMapper;
    }

    /** Imports every statement; returns account ids by slug. */
    Map<String, Long> load() throws Exception {
        Map<String, Long> accounts = new LinkedHashMap<>();
        for (String slug : SLUGS) {
            accounts.put(slug, account(slug));
        }
        for (String[] entry : manifest()) {
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

package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.categorize.CategorizationPromptBuilder.Candidate;
import com.finalysis.categorize.CategorizationPromptBuilder.CategorizationPrompt;
import com.finalysis.categorize.CategorizationPromptBuilder.LocalExample;
import com.finalysis.categorize.CategorizationPromptBuilder.Skip;
import com.finalysis.categorize.MerchantCleaner.CleanedMerchant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The payload test (SEC-01, SEC-08; docs/plans/phase-4.md §4.6), step 5's form: synthetic raw descriptions full
 * of markers go through the real cleaner, {@link Redactor}, {@link OutboundPayloadGuard} and
 * {@link CategorizationPromptBuilder}, and what would be sent is compared with a document written by hand. Step 7
 * runs the same check on the {@code Prompt} a capturing fake model receives from the real pipeline.
 */
class OutboundPayloadTest {

    /** The seeded category names (V2), in the order the request must list them. */
    private static final List<String> CATEGORIES = List.of("Bank Fees", "Cash & ATM", "Dining", "Groceries",
            "HOA & Condo Fees", "Health", "Housing", "Income", "Interest", "Mortgage", "Person-to-Person In",
            "Person-to-Person Out", "Refunds & Rebates", "Shopping", "Subscriptions", "Tolls", "Transfer",
            "Transportation", "Utilities");

    private static final String ACCOUNT_NAME = "Sample Family Checking";
    private static final String INSTITUTION = "Testfield Credit Union";
    private static final String LAST_FOUR = "4821";

    private final MerchantCleaner cleaner = new MerchantCleaner(MerchantCleaningRules.loadDefault());
    private final CategorizationPromptBuilder builder = new CategorizationPromptBuilder(
            new Redactor(MerchantCleaningRules.loadDefault()), new OutboundPayloadGuard());
    private final JsonMapper json = JsonMapper.builder().build();

    /** A candidate as the pipeline will make it: the description cleaned, as at import. */
    private Candidate candidate(String rawDescription, String amount, LocalExample... similar) {
        CleanedMerchant cleaned = cleaner.clean(rawDescription);
        return new Candidate(cleaned.merchant(), cleaned.counterparty(), new BigDecimal(amount), List.of(similar));
    }

    private LocalExample example(String rawDescription, String category) {
        return new LocalExample(cleaner.clean(rawDescription).merchant(), category);
    }

    @Test
    void whatWouldBeSentIsExactlyTheHandWrittenDocument() {
        List<String> raw = List.of(
                "ACME INS PREM INDN:SAMPLE JANE CO ID:9900112233 PPD",
                "ONLINE TRANSFER TO SAVINGS XXXXXX4821",
                "ZELLE PAYMENT TO JANE Q SAMPLE CONF# 8XK2P1",
                "STARBUCKS ARLINGTON VA",
                "PAYPAL *NOVA BOOKS JANE.SAMPLE@EXAMPLE.COM",
                "SAMPLE FAMILY CHECKING BILL PAY 4821 CITY WATER",
                "PAYROLL ID: 4419920113 BRIGHTWAY");
        List<Candidate> candidates = List.of(
                candidate(raw.get(0), "-120.00"),
                candidate(raw.get(1), "-500.00"),
                candidate(raw.get(2), "-50.00"),
                candidate(raw.get(3), "-5.75",
                        example("BLUE BEAN CAFE ARLINGTON VA", "Dining"),
                        example("TESTFIELD CREDIT UNION ATM 4821", "Cash & ATM")),
                candidate(raw.get(4), "-18.40"),
                candidate(raw.get(5), "-61.37"),
                candidate(raw.get(6), "2500.00"));
        RedactionContext context = new RedactionContext(Set.of(LAST_FOUR), List.of(ACCOUNT_NAME, INSTITUTION),
                Set.of("JANE Q SAMPLE"));

        CategorizationPrompt prompt = builder.build(CATEGORIES, candidates, context);

        String user = prompt.user();
        assertThat(user).startsWith("<transactions>").endsWith("</transactions>");
        String body = user.substring("<transactions>".length(), user.length() - "</transactions>".length());
        assertThat(json.readTree(body)).isEqualTo(json.readTree("""
                {
                  "allowedCategories": ["Bank Fees", "Cash & ATM", "Dining", "Groceries", "HOA & Condo Fees", "Health",
                                        "Housing", "Income", "Interest", "Mortgage", "Person-to-Person In",
                                        "Person-to-Person Out", "Refunds & Rebates", "Shopping", "Subscriptions",
                                        "Tolls", "Transfer", "Transportation", "Utilities"],
                  "items": [
                    {"key": "i1", "merchant": "ACME INS PREM", "amount": "-120.00", "similar": []},
                    {"key": "i2", "merchant": "ONLINE TRANSFER TO SAVINGS", "amount": "-500.00", "similar": []},
                    {"key": "i3", "merchant": "STARBUCKS", "amount": "-5.75", "similar": [
                      {"merchant": "BLUE BEAN CAFE", "category": "Dining"},
                      {"merchant": "ATM", "category": "Cash & ATM"}]},
                    {"key": "i4", "merchant": "NOVA BOOKS", "amount": "-18.40", "similar": []},
                    {"key": "i5", "merchant": "BILL PAY CITY WATER", "amount": "-61.37", "similar": []},
                    {"key": "i6", "merchant": "PAYROLL BRIGHTWAY", "amount": "2500.00", "similar": []}
                  ]
                }"""));
        assertThat(prompt.skipped()).isEqualTo(Map.of(2, Skip.PERSON_TO_PERSON));
        assertThat(prompt.candidateByKey()).isEqualTo(Map.of("i1", 0, "i2", 1, "i3", 3, "i4", 4, "i5", 5, "i6", 6));

        String everything = prompt.system() + "\n" + user;
        for (String marker : List.of(LAST_FOUR, "XXXXXX", "INDN", "SAMPLE", "JANE", "9900112233", "8XK2P1", "CO ID",
                "EXAMPLE.COM", "@", "ARLINGTON", "TESTFIELD", "CREDIT UNION", "FAMILY", "4419920113", "ZELLE",
                "PAYPAL", "PPD", "2026")) {
            assertThat(everything.toUpperCase()).as("marker " + marker).doesNotContain(marker);
        }
        for (String description : raw) {
            assertThat(everything).doesNotContain(description);
        }
    }

    /** §5.2: the outbound copy strips the location; the merchant as stored keeps it. */
    @Test
    void theStoredMerchantKeepsWhatTheOutboundCopyDrops() {
        Candidate starbucks = candidate("STARBUCKS ARLINGTON VA", "-5.75");

        CategorizationPrompt prompt = builder.build(CATEGORIES, List.of(starbucks), RedactionContext.EMPTY);

        assertThat(starbucks.merchant()).isEqualTo("STARBUCKS ARLINGTON VA");
        assertThat(prompt.request().items().getFirst().merchant()).isEqualTo("STARBUCKS");
    }
}

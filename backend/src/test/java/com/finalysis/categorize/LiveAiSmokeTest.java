package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.ai.outbound.RedactionContextQuery;
import com.finalysis.categorize.CategorizationModelClient.Result;
import com.finalysis.categorize.CategorizationPromptBuilder.Candidate;
import com.finalysis.categorize.CategorizationPromptBuilder.CategorizationPrompt;
import com.finalysis.categorize.CategorizationResponse.Answer;
import com.finalysis.categorize.MerchantCleaner.CleanedMerchant;
import com.finalysis.support.IntegrationTest;
import com.finalysis.support.NoCloudAiGuard;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.ActiveProfiles;

/**
 * Opt-in live smoke test: one synthetic merchant through the real path to the cloud model and back. Never part of
 * {@code ./mvnw verify}: it is tagged {@value NoCloudAiGuard#LIVE_TAG} (excluded by default) and also needs
 * {@code -Dfinalysis.live-ai=true}. The {@code ai-smoke} Maven profile sets both:
 * <pre>cd backend &amp;&amp; ./mvnw test -Pai-smoke</pre>
 * It runs on Testcontainers with the {@code aws} profile and the AWS CLI profile named in application-aws.yml.
 *
 * <p>The description is synthetic and its merchant appears in no sample, dev or holdout file. It goes through
 * cleaning, the redaction context read from the (empty) test database, the {@link com.finalysis.ai.outbound.Redactor}, the
 * {@link com.finalysis.ai.outbound.OutboundPayloadGuard} and the {@link CategorizationPromptBuilder}, exactly as stage 5 will send it. The
 * test prints one line: the category, the token counts and the latency. Nothing else of the answer is printed.
 */
@Tag(NoCloudAiGuard.LIVE_TAG)
@EnabledIfSystemProperty(named = NoCloudAiGuard.LIVE_PROPERTY, matches = "true")
@IntegrationTest
@ActiveProfiles("aws")
class LiveAiSmokeTest {

    static final String DESCRIPTION = "TST* BRAMBLEWICK NOODLE HOUSE 0193 ARLINGTON VA";
    static final BigDecimal AMOUNT = new BigDecimal("-18.40");

    private final MerchantCleaner cleaner;
    private final RedactionContextQuery contextQuery;
    private final CategoryRepository categories;
    private final CategorizationPromptBuilder promptBuilder;
    private final CategorizationModelClient client;

    LiveAiSmokeTest(MerchantCleaner cleaner, RedactionContextQuery contextQuery, CategoryRepository categories,
                    CategorizationPromptBuilder promptBuilder, CategorizationModelClient client) {
        this.cleaner = cleaner;
        this.contextQuery = contextQuery;
        this.categories = categories;
        this.promptBuilder = promptBuilder;
        this.client = client;
    }

    @Test
    void oneSyntheticMerchantIsCategorizedByTheCloudModel() {
        CleanedMerchant cleaned = cleaner.clean(DESCRIPTION);
        List<String> allowed = categories.findAll().stream().map(Category::getName).toList();
        CategorizationPrompt prompt = promptBuilder.build(allowed,
                List.of(new Candidate(cleaned.merchant(), cleaned.counterparty(), AMOUNT, List.of())), contextQuery.load());

        // The guard let it through, redacted: one item, no store number or location.
        assertThat(prompt.skipped()).isEmpty();
        assertThat(prompt.request().items()).singleElement()
                .satisfies(item -> assertThat(item.merchant()).isEqualTo("BRAMBLEWICK NOODLE HOUSE"));

        Result result = client.categorize(prompt);

        String category = !result.parsed() ? "(output did not parse)" : result.response().answers().stream()
                .filter(answer -> "i1".equals(answer.key()))
                .map(Answer::category)
                .filter(allowed::contains)
                .findFirst().orElse("(no allowed category for i1)");
        System.out.printf("ai-smoke category=%s inputTokens=%s outputTokens=%s ms=%d%n",
                category, result.inputTokens(), result.outputTokens(), result.millis());
        assertThat(allowed).contains(category);
    }
}

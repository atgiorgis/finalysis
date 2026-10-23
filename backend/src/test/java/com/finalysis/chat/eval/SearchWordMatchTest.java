package com.finalysis.chat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.chat.ToolCall;
import com.finalysis.chat.ToolExecutor;
import com.finalysis.chat.ToolOutcome;
import com.finalysis.chat.ToolStatus;
import com.finalysis.chat.outbound.OutboundTurnFactory;
import com.finalysis.support.ChatIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 8e: {@code search_transactions}' word match on the sample data, seeded as the evaluation seeds it, with the exact
 * brand searches of the run-3 dev evals (7f09a06) that found nothing although the merchant exists: the name match
 * needed every word of the search in one name. Now any useful word finds a merchant, as match WORD; a name holding every
 * word is still a NAME match, and generic words alone find nothing by name.
 */
@ChatIntegrationTest
@AutoConfigureMockMvc
@Import(ChatEvalBeans.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchWordMatchTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ChatEvalSeeder seeder;
    private final ToolExecutor executor;
    private final OutboundTurnFactory turns;

    SearchWordMatchTest(ChatEvalSeeder seeder, ToolExecutor executor, OutboundTurnFactory turns) {
        this.seeder = seeder;
        this.executor = executor;
        this.turns = turns;
    }

    @BeforeAll
    void seed() throws Exception {
        seeder.seed("SAMPLE");
    }

    @AfterAll
    void clear() {
        seeder.clear();
    }

    /** The search's candidates by merchant → match. */
    private Map<String, String> search(String query) {
        ToolOutcome outcome = executor.execute(new ToolCall("search_transactions",
                "{\"query\": " + JSON.writeValueAsString(query) + ", \"limit\": 10}"), turns.start());
        assertThat(outcome.status()).as(outcome.json()).isEqualTo(ToolStatus.OK);
        JsonNode candidates = JSON.readTree(outcome.json()).get("result").get("candidates");
        return candidates.valueStream().collect(Collectors.toMap(c -> c.get("merchant").stringValue(),
                c -> c.get("match").stringValue(), (a, b) -> a, java.util.LinkedHashMap::new));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Shell Chevron BP ExxonMobil Speedway|SHELL OIL",
            "Chipotle Qdoba Moe's Baja Fresh Del Taco|CHIPOTLE",
            "DoorDash Uber Eats Grubhub|DOORDASH THAI BASIL",
            "Comcast Xfinity AT&T Spectrum internet|SKYWAVE INTERNET AUTOPAY"})
    void theRun3BrandSearchesFindTheirMerchant(String query, String merchant) {
        assertThat(search(query)).containsEntry(merchant, "WORD");
    }

    @Test
    void wordMatchesComeRightAfterNameMatches() {
        List<String> found = List.copyOf(search("DoorDash Uber Eats Grubhub").keySet());
        assertThat(found.subList(0, 2)).as("one word each, in name order")
                .containsExactly("DOORDASH THAI BASIL", "UBER TRIP");
    }

    @Test
    void theMatchIgnoresCase() {
        assertThat(search("shell")).containsEntry("SHELL OIL", "NAME");
        assertThat(search("shell chevron")).containsEntry("SHELL OIL", "WORD");
    }

    @Test
    void aNameHoldingEveryWordIsStillANameMatch() {
        assertThat(search("internet")).containsEntry("SKYWAVE INTERNET AUTOPAY", "NAME");
        assertThat(search("internet bill")).as("bill is generic: the word match finds it")
                .containsEntry("SKYWAVE INTERNET AUTOPAY", "WORD");
    }

    @Test
    void genericWordsAloneFindNothingByName() {
        assertThat(search("the bill payment for my card").values()).doesNotContain("NAME", "WORD");
        assertThat(search("autopay").values()).as("a whole name word is still a name match").contains("NAME");
    }
}

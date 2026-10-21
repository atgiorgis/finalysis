package com.finalysis.chat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.analytics.MoneyFlow;
import com.finalysis.chat.citation.Citation;
import com.finalysis.chat.citation.Citation.CitedTxn;
import com.finalysis.chat.eval.ChatEvalSet.Locator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** The runner's local checks (step 8) without a context: expected citations, and which settings are recorded. */
class ChatEvalRunnerTest {

    private static final Map<String, Long> ACCOUNTS = Map.of("apex-visa", 3L, "summit-card", 4L);

    private static CitedTxn txn(long account, String date, String line) {
        return new CitedTxn(CitedTxn.Status.FOUND, "fp", 1L, account, "Apex Visa", LocalDate.parse(date), null,
                new BigDecimal("-84.12"), "SAFEWAY", "Groceries", null, new Citation.SourceLine(7, line));
    }

    private static final Locator SAFEWAY = new Locator("apex-visa", LocalDate.of(2026, 6, 13), "SAFEWAY");

    @Test
    void anExpectedRowIsFoundInATransactionCitationOrAnAlertsTransactions() {
        CitedTxn row = txn(3, "2026-06-13", "06/13 SAFEWAY #1234 SEATTLE WA 84.12");

        assertThat(ChatEvalRunner.citationsFound(List.of(SAFEWAY), List.of(new Citation.Txn(1, row)), ACCOUNTS))
                .isTrue();
        assertThat(ChatEvalRunner.citationsFound(List.of(SAFEWAY),
                List.of(new Citation.Alert(1, "k", "POSSIBLE_DUPLICATE", null, List.of(row))), ACCOUNTS)).isTrue();
    }

    @Test
    void anExpectedRowIsFoundInADrillDownsSampleTransactions() {
        Citation.DrillDown drill = new Citation.DrillDown(Citation.DrillDown.PATH, Map.of(),
                new MoneyFlow(new BigDecimal("84.12"), BigDecimal.ZERO, new BigDecimal("-84.12"), 1),
                List.of(txn(3, "2026-06-13", "SAFEWAY")));

        assertThat(ChatEvalRunner.citationsFound(List.of(SAFEWAY),
                List.of(new Citation.Total(1, "Groceries", List.of(), List.of(drill))), ACCOUNTS)).isTrue();
    }

    @Test
    void anotherAccountDateOrRowIsNotTheExpectedOne() {
        assertThat(ChatEvalRunner.citationsFound(List.of(SAFEWAY),
                List.of(new Citation.Txn(1, txn(4, "2026-06-13", "SAFEWAY"))), ACCOUNTS)).isFalse();
        assertThat(ChatEvalRunner.citationsFound(List.of(SAFEWAY),
                List.of(new Citation.Txn(1, txn(3, "2026-06-14", "SAFEWAY"))), ACCOUNTS)).isFalse();
        assertThat(ChatEvalRunner.citationsFound(List.of(SAFEWAY),
                List.of(new Citation.Txn(1, CitedTxn.gone("fp"))), ACCOUNTS)).isFalse();
        assertThat(ChatEvalRunner.citationsFound(List.of(SAFEWAY), List.of(), ACCOUNTS)).isFalse();
    }

    @Test
    void aLocatorWithoutAccountOrDateMatchesOnItsWords() {
        Locator words = new Locator(null, null, "SAFEWAY");

        assertThat(ChatEvalRunner.citationsFound(List.of(words),
                List.of(new Citation.Txn(1, txn(4, "2026-01-02", "safeway store"))), ACCOUNTS)).isTrue();
    }

    @Test
    void noExpectedCitationIsNoCheck() {
        assertThat(ChatEvalRunner.citationsFound(List.of(), List.of(), ACCOUNTS)).isNull();
    }

    @Test
    void theSettingsAreEveryChatAndAiPropertyButProfileNames() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "finalysis.chat.max-input-tokens-per-call", "12000",
                "finalysis.chat.search.min-similarity", "0.40",
                "finalysis.ai.roles.main", "a-model",
                "spring.ai.bedrock.aws.region", "us-east-1",
                "spring.ai.bedrock.aws.profile.name", "someone",
                "spring.datasource.password", "x",
                "server.port", "0")));

        assertThat(ChatEvalRunner.settings(environment)).containsOnlyKeys("finalysis.chat.max-input-tokens-per-call",
                "finalysis.chat.search.min-similarity", "finalysis.ai.roles.main", "spring.ai.bedrock.aws.region");
    }
}

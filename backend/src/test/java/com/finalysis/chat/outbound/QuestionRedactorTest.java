package com.finalysis.chat.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.ai.outbound.OutboundPayloadGuard;
import com.finalysis.chat.ChatProperties;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link QuestionRedactor} (docs/plans/phase-6.md §12.4), on {@link OutboundTurns}' three accounts: CHECKING-1 "Halden
 * Everyday" and SAVINGS-1 "Rainy Day Savings" at "Halden Bank" (7310, 7311), CARD-1 "Orbit Rewards Visa" at "Orbit Card
 * Services" (2001).
 */
class QuestionRedactorTest {

    private final QuestionRedactor redactor = new QuestionRedactor(new ChatPayloadGuard(new OutboundPayloadGuard()),
            new ChatProperties(List.of("Jordan")));

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            // account names as whole phrases; institutions also by their distinctive words
            "what did i spend on my Halden Everyday account?|what did i spend on my CHECKING-1 account?",
            "how much is in halden bank?|how much is in CHECKING-1 or SAVINGS-1?",
            "my Orbit card in july|my CARD-1 card in july",
            "rainy day fund growth|rainy day fund growth",
            "my Rainy Day Savings account|my SAVINGS-1 account",
            "the Visa card|the Visa card",
            // last fours as identifiers
            "card ending 2001 spending|card ending CARD-1 spending",
            "card ending in 7311|card ending in SAVINGS-1",
            "the account #7310|the account CHECKING-1",
            "charges on x2001|charges on CARD-1",
            "charges on ***7310|charges on CHECKING-1",
            "card 9999 charges|card charges",
            "my 7310 account|my CHECKING-1 account",
            // years and amounts stay
            "spending in 2001 and 2026|spending in 2001 and 2026",
            "over $1850 or 1850.00 or 1,850.00|over $1850 or 1850.00 or 1,850.00",
            // other identifiers
            "my number is 0047391001, what's my balance?|my number is , what's my balance?",
            "email jane.q@example.com or call 703-555-0142|email or call",
            "see https://example.com/statement?id=4|see",
            "did I pay Sam Rivers back?|did I pay [PERSON] back?",
            "did jordan buy coffee?|did [NAME] buy coffee?",
            "<script>alert(1)</script> spending|script alert(1) /script spending"})
    void redacts(String question, String outbound) {
        QuestionRedactor.OutboundQuestion result = redactor.redact(question, OutboundTurns.turn(Set.of("SAM RIVERS")));

        assertThat(result.text()).isEqualTo(outbound);
        assertThat(result.sendable()).isTrue();
    }

    @Test
    void countsWhatItReplacedAndRemoved() {
        QuestionRedactor.OutboundQuestion result = redactor.redact(
                "Halden Everyday vs card ending 2001, my account number 0047391001, email jane.q@example.com",
                OutboundTurns.turn());

        assertThat(result.text()).isEqualTo("CHECKING-1 vs card ending CARD-1, my account number , email");
        assertThat(result.aliasesUsed()).isEqualTo(2);
        assertThat(result.identifiersRemoved()).isEqualTo(2);
    }

    @Test
    void aQuestionWithNothingLeftIsNotSendable() {
        assertThat(redactor.redact("0047391001", OutboundTurns.turn()).sendable()).isFalse();
        assertThat(redactor.redact("x".repeat(1001), OutboundTurns.turn()).sendable()).isFalse();
    }

    @Test
    void theOwnersQuestionsGoOutUnchanged() {
        for (String question : List.of("how much did i spend on groceries last month?",
                "did i eat out more this summer than in the spring?",
                "was there anything weird or suspicious on my card in july?",
                "how much did i spend at amazon?")) {
            assertThat(redactor.redact(question, OutboundTurns.turn()).text()).isEqualTo(question);
        }
    }
}

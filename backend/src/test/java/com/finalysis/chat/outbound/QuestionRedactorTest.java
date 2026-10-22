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
            com.finalysis.chat.ChatLimits.withRedactTerms(List.of("Jordan")));

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            // account names and institutions as whole phrases, then by their distinctive words (step 8b)
            "what did i spend on my Halden Everyday account?|what did i spend on my CHECKING-1 account?",
            "how much is in halden bank?|how much is in CHECKING-1 or SAVINGS-1?",
            "my Orbit card in july|my CARD-1 card in july",
            "my Rainy Day Savings account|my SAVINGS-1 account",
            "is my everyday account low?|is my CHECKING-1 account low?",
            "Which had more, Everyday or Orbit?|Which had more, CHECKING-1 or CARD-1?",
            "rainy day fund growth|SAVINGS-1 day fund growth",
            "the rewards on my orbit rewards|the rewards on my CARD-1 rewards",
            // words that are never an account by themselves: generic, everyday, partial words
            "the Visa card|the Visa card",
            "what day did I pay rent?|what day did I pay rent?",
            "my checking, savings and card account|my checking, savings and card account",
            "orbits, haldenville and everydays|orbits, haldenville and everydays",
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
    void aWordOfACategoryOrOfANameableMerchantIsNeverAnAccountByItself() {
        OutboundTurn diningCard = OutboundTurns.withIdentities(List.of(
                new AccountIdentity(11, "Halden Everyday", "Halden Bank", "7310"),
                new AccountIdentity(12, "Rainy Day Savings", "Halden Bank", "7311"),
                new AccountIdentity(13, "Dining Rewards Visa", "Orbit Card Services", "2001")));
        assertThat(redactor.redact("what did I spend on dining?", diningCard).text())
                .isEqualTo("what did I spend on dining?");
        assertThat(redactor.redact("my Dining Rewards Visa in july", diningCard).text())
                .isEqualTo("my CARD-1 in july");

        OutboundTurn orbitCoffee = OutboundTurns.withMerchants(List.of("ORBIT COFFEE ROASTERS"));
        assertThat(redactor.redact("how much at orbit coffee?", orbitCoffee).text())
                .isEqualTo("how much at orbit coffee?");
        assertThat(redactor.redact("my Orbit Rewards Visa in july", orbitCoffee).text())
                .isEqualTo("my CARD-1 in july");
        assertThat(redactor.redact("the halden account", orbitCoffee).text())
                .isEqualTo("the CHECKING-1 or SAVINGS-1 account");
    }

    @Test
    void theSampleAccountsByOneWord() {
        OutboundTurn sample = OutboundTurns.withIdentities(List.of(
                new AccountIdentity(11, "Northfield Checking", "Northfield Credit Union", "1001"),
                new AccountIdentity(12, "Northfield Savings", "Northfield Credit Union", "1002"),
                new AccountIdentity(13, "Summit Card", "Summit Financial", "3001")));
        assertThat(redactor.redact("What did I spend on my Summit card in August?", sample).text())
                .isEqualTo("What did I spend on my CARD-1 in August?");
        assertThat(redactor.redact("Which card had more spending in June, Northfield or Summit?", sample).text())
                .isEqualTo("Which card had more spending in June, CHECKING-1 or SAVINGS-1 or CARD-1?");
        assertThat(redactor.redact("my northfield checking account", sample).text())
                .isEqualTo("my CHECKING-1 account");
        assertThat(redactor.redact("the summit of my spending", sample).text())
                .isEqualTo("the CARD-1 of my spending"); // a known cost: a distinctive word is an account
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

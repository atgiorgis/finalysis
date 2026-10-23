package com.finalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.chat.grounding.SummaryFigureStripper;
import com.finalysis.chat.outbound.ChatPayloadGuard;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * An earlier answer as it goes back as history (CHT-06, step 8f), no Spring: figures stripped, at most
 * {@value ConversationMemory#MAX_ANSWER_CHARS} characters, and a cut answer marked as shortened. Dev run 4's d-m03:
 * Haiku's five-alert answer was cut at 1,000 characters, just before the possible duplicate, and the next turn ("Tell
 * me more about the duplicate") said there was none.
 */
class HistoryAnswerTest {

    /** Haiku 4.5's d-m03 turn 1 in dev run 4 (cc040ba), on the synthetic sample data, as shown. */
    static final String FIVE_ALERTS = """
            You have 5 alerts across the period from March to October 2026: [1, 2, 3, 4, 5]

            **Category Spikes (2 alerts):**

            1. **Dining in August 2026** [1]: You spent $340.63 on Dining, up 63.9% from your baseline of $207.79. This exceeded the threshold by $29. Notable transactions include Nakamura Ramen ($43.88), Blue Bean Cafe ($42.09), and Chipotle ($38.65).

            2. **Shopping in August 2026** [2]: You spent $1,183.67 on Shopping, a spike of 384.3% above your baseline of $244.42. This was driven by a $899.99 purchase at Best Buy along with Amazon and Target purchases.

            **Large Transaction (1 alert):**

            3. **Best Buy in August 2026** [3]: A $899.99 transaction at Best Buy was flagged as unusually large—13.55 times your median Shopping transaction amount of $66.43. Note: CARD-1 had partial data coverage that month.

            **Recurring Price Increase (1 alert):**

            4. **Spotify USA in July 2026** [4]: Your Spotify subscription increased from $11.99 to $12.99, an 8.3% increase.

            **Possible Duplicate (1 alert):**

            5. **Safeway on June 13, 2026** [5]: Two identical transactions of $84.12 appeared on the same day at Safeway, which may be a duplicate charge.""";

    @Test
    void theRun4AlertListKeepsEveryAlertsKindAndMerchant() {
        String history = ConversationMemory.strippedAnswer(FIVE_ALERTS);

        assertThat(history).contains("Category Spikes", "Dining", "Shopping", "Large Transaction", "Best Buy",
                "Recurring Price Increase", "Spotify USA", "Possible Duplicate", "Safeway on June 13, 2026");
        assertThat(SummaryFigureStripper.figureFree(history)).isTrue();
        assertThat(history).doesNotContain("$84.12", "84.12", "[5]").doesNotEndWith(ConversationMemory.SHORTENED);
    }

    @Test
    void aCutAnswerEndsWithTheMarkerAndStaysWithinTheCap() {
        String longAnswer = (FIVE_ALERTS + "\n\n").repeat(3);

        String history = ConversationMemory.strippedAnswer(longAnswer);

        assertThat(history).endsWith(ConversationMemory.SHORTENED);
        assertThat(history.length()).isLessThanOrEqualTo(ConversationMemory.MAX_ANSWER_CHARS)
                .isLessThanOrEqualTo(ChatPayloadGuard.MAX_HISTORY);
        assertThat(history).as("cut at a word, once").doesNotContain("……");
        assertThat(SummaryFigureStripper.figureFree(history)).isTrue();
    }

    @Test
    void anUncutAnswerHasNoMarker() {
        assertThat(ConversationMemory.strippedAnswer("You spent $190.10 on dining in August [1]."))
                .isEqualTo("You spent [amount] on dining in August.");
        String exactly = "a".repeat(ConversationMemory.MAX_ANSWER_CHARS);
        assertThat(ConversationMemory.strippedAnswer(exactly)).isEqualTo(exactly);
        assertThat(ConversationMemory.strippedAnswer(exactly + "b")).endsWith(ConversationMemory.SHORTENED);
    }

    @Test
    void theMarkerHasNoFigure() {
        assertThat(SummaryFigureStripper.figureFree(ConversationMemory.SHORTENED)).isTrue();
        assertThat(SummaryFigureStripper.strip(ConversationMemory.SHORTENED)).isEqualTo(ConversationMemory.SHORTENED);
    }

    /** The system prompt names the marker as it is written (chat-8). */
    @Test
    void thePromptNamesTheMarker() throws IOException {
        try (InputStream in = new ClassPathResource(ChatPrompt.RESOURCE).getInputStream()) {
            String prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(ConversationMemory.SHORTENED).startsWith("… [earlier answer shortened");
            assertThat(prompt).contains("\"[earlier answer shortened ...]\"");
        }
    }
}

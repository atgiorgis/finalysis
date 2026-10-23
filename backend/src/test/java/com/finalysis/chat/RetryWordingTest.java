package com.finalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.chat.grounding.AnswerCheck;
import com.finalysis.chat.grounding.AnswerGrounding;
import com.finalysis.chat.grounding.Figure;
import com.finalysis.chat.grounding.RetryWordingFixture;
import com.finalysis.chat.grounding.SummaryFigureStripper;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The two automatic retry messages (CHT-04, step 9a), no Spring. They go to the model as user messages, and in dev run 5
 * the shown answers began "You're right, I apologize" (Sonnet d-s08, d-m03) and "You're right." (Haiku d-x08): the
 * model answered a correction the user never made. Both now say they are an automatic check, not the user, and ask for
 * a new answer to the user's question; neither has wording that reads as a correction or invites an apology.
 */
class RetryWordingTest {

    /** Words of a correction, or of an apology: none may be in a retry message. */
    private static final Pattern INVITES_AN_APOLOGY = Pattern.compile(
            "\\b(?:wrong|mistakes?|mistaken|incorrect|errors?|erroneous|sorry|apolog\\w*|correct\\w*|right|fix\\w*"
                    + "|rewrite|redo|try again|you said|you gave|your answer)\\b");

    private static List<String> retries() {
        AnswerCheck check = RetryWordingFixture.ungrounded();
        return List.of(new AnswerGrounding(RetryWordingFixture.GUARD).retryMessage(check),
                TurnLoop.CUT_ANSWER_RETRY);
    }

    @Test
    void neitherRetryInvitesAnApology() {
        for (String retry : retries()) {
            assertThat(INVITES_AN_APOLOGY.matcher(retry.toLowerCase(Locale.ROOT)).find()).as(retry).isFalse();
        }
    }

    @Test
    void bothSayTheyAreAnAutomaticCheckAndAskForANewAnswerToTheQuestion() {
        for (String retry : retries()) {
            assertThat(retry).startsWith("Automatic check, not a message from the user: ")
                    .contains("Write the answer to the user's question again from the start");
        }
        assertThat(retries().getFirst()).startsWith(AnswerGrounding.RETRY_PREFIX)
                .endsWith(AnswerGrounding.RETRY_INSTRUCTION).contains("as a new answer to that question");
    }

    @Test
    void theFixedWordingHasNoFigure() {
        assertThat(SummaryFigureStripper.figureFree(AnswerGrounding.RETRY_PREFIX + AnswerGrounding.RETRY_INSTRUCTION))
                .isTrue();
        assertThat(SummaryFigureStripper.figureFree(TurnLoop.CUT_ANSWER_RETRY)).isTrue();
        assertThat(RetryWordingFixture.ungrounded().ungrounded())
                .extracting(Figure::asWritten).containsExactly("$116.21");
    }
}

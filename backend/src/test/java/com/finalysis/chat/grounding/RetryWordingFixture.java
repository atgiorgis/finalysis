package com.finalysis.chat.grounding;

import com.finalysis.chat.outbound.ChatPayloadGuard;

/** An ungrounded answer for {@code RetryWordingTest}, which lives beside the turn loop's own retry. */
public final class RetryWordingFixture {

    public static final ChatPayloadGuard GUARD = Evidence.GUARD;

    private RetryWordingFixture() {
    }

    /** "$116.21" is in no tool result of the shopping spike. */
    public static AnswerCheck ungrounded() {
        return Evidence.shoppingSpike().check("You spent $1,183.67, $116.21 more than in July [a1].");
    }

    /** A history placeholder copied into the answer (step 10-1), and nothing else wrong. */
    public static AnswerCheck placeholder() {
        return Evidence.shoppingSpike().check("You spent [amount] on Shopping [a1].");
    }

    /** Both: an ungrounded figure and a placeholder. */
    public static AnswerCheck ungroundedAndPlaceholder() {
        return Evidence.shoppingSpike().check("You spent [amount], $116.21 more than in July [a1].");
    }
}

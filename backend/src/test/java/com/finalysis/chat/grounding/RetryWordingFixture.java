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
}

package com.finalysis.chat.outbound;

import com.finalysis.ai.outbound.OutboundPayloadGuard;
import com.finalysis.ai.outbound.RedactionContext;
import com.finalysis.ai.outbound.Redactor;
import org.springframework.stereotype.Component;

/**
 * A stored merchant's outbound text (docs/plans/phase-6.md §12.2, §12.5): the {@link Redactor}'s copy if the
 * {@link OutboundPayloadGuard} allows it and {@link InstructionLikeText} doesn't object, otherwise {@value #WITHHELD}.
 * Totals stay complete either way; only the name is held back. Person-to-person merchants are never sent.
 */
@Component
public class MerchantOutbound {

    /** What the model sees instead of a merchant that may not go. It passes the guard itself. */
    public static final String WITHHELD = "MERCHANT WITHHELD";

    private final Redactor redactor;
    private final OutboundPayloadGuard guard;
    private final InstructionLikeText instructions;

    public MerchantOutbound(Redactor redactor, OutboundPayloadGuard guard, InstructionLikeText instructions) {
        this.redactor = redactor;
        this.guard = guard;
        this.instructions = instructions;
    }

    /** How a merchant went out. */
    public enum Outcome {
        /** Sent as its redacted text. */
        SENT,
        /** Withheld: instruction-like, or an amount in the name ({@link InstructionLikeText}). */
        WITHHELD,
        /** Refused: person-to-person, nothing left after redaction, or the guard said no. */
        REFUSED
    }

    /** The decision for one merchant. */
    public record Decision(String text, Outcome outcome) {
    }

    public Decision decide(String merchant, RedactionContext context) {
        if (redactor.isPersonToPerson(merchant, null)) {
            return new Decision(WITHHELD, Outcome.REFUSED);
        }
        String redacted = redactor.redact(merchant, context);
        if (redacted == null || !guard.allowsMerchant(redacted, context)) {
            return new Decision(WITHHELD, Outcome.REFUSED);
        }
        if (instructions.check(redacted) != null) {
            return new Decision(WITHHELD, Outcome.WITHHELD);
        }
        return new Decision(redacted, Outcome.SENT);
    }

    /** The guard's own check of a merchant field, independent of how the projector decided it. */
    public boolean mayGo(String text, RedactionContext context) {
        return WITHHELD.equals(text) || guard.allowsMerchant(text, context) && instructions.check(text) == null;
    }
}

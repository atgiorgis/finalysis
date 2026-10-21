package com.finalysis.chat.outbound;

import com.finalysis.ai.outbound.OutboundPayloadGuard;
import com.finalysis.ai.outbound.RedactionContext;
import com.finalysis.ai.outbound.Redactor;
import com.finalysis.categorize.MerchantCleaningRules;
import com.finalysis.chat.tool.AccountAliases;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds {@link OutboundTurn}s for unit tests, with the real redactor, guard and word list and no database. */
final class OutboundTurns {

    static final MerchantOutbound MERCHANTS = new MerchantOutbound(new Redactor(MerchantCleaningRules.loadDefault()),
            new OutboundPayloadGuard(), new InstructionLikeText());

    /** Accounts 11 (checking, "Halden Everyday" at "Halden Bank", 7310), 12 (savings, same bank, 7311), 13 (card). */
    static final List<AccountIdentity> IDENTITIES = List.of(
            new AccountIdentity(11, "Halden Everyday", "Halden Bank", "7310"),
            new AccountIdentity(12, "Rainy Day Savings", "Halden Bank", "7311"),
            new AccountIdentity(13, "Orbit Rewards Visa", "Orbit Card Services", "2001"));

    private OutboundTurns() {
    }

    static OutboundTurn turn() {
        return turn(Set.of());
    }

    static OutboundTurn turn(Set<String> counterparties) {
        AccountAliases aliases = AccountAliases.of(List.of(new AccountAliases.Account(11, "CHECKING", false),
                new AccountAliases.Account(12, "SAVINGS", false), new AccountAliases.Account(13, "CREDIT_CARD", false)));
        RedactionContext context = new RedactionContext(Set.of("7310", "7311", "2001"),
                List.of("Halden Everyday", "Halden Bank", "Rainy Day Savings", "Orbit Rewards Visa",
                        "Orbit Card Services"), counterparties);
        return new OutboundTurn(aliases, context, IDENTITIES, Map.of("Dining", 1L, "Groceries", 2L), MERCHANTS);
    }
}

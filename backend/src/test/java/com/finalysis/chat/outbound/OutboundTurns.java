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

    /** As {@link #turn()}, with these stored merchants in the data (each named by its redacted text). */
    static OutboundTurn withMerchants(List<String> storedMerchants) {
        return turn(Set.of(), IDENTITIES, storedMerchants);
    }

    /** As {@link #turn()}, with these identities for accounts 11, 12 and 13. */
    static OutboundTurn withIdentities(List<AccountIdentity> identities) {
        return turn(Set.of(), identities, List.of());
    }

    static OutboundTurn turn(Set<String> counterparties) {
        return turn(counterparties, IDENTITIES, List.of());
    }

    private static OutboundTurn turn(Set<String> counterparties, List<AccountIdentity> identities,
                                     List<String> storedMerchants) {
        AccountAliases aliases = AccountAliases.of(List.of(new AccountAliases.Account(11, "CHECKING", false),
                new AccountAliases.Account(12, "SAVINGS", false), new AccountAliases.Account(13, "CREDIT_CARD", false)));
        RedactionContext context = new RedactionContext(Set.of("7310", "7311", "2001"),
                identities.stream().flatMap(i -> java.util.stream.Stream.of(i.name(), i.institution())).toList(),
                counterparties);
        return new OutboundTurn(aliases, context, identities, Map.of("Dining", 1L, "Groceries", 2L), MERCHANTS,
                () -> storedMerchants, null);
    }
}

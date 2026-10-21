package com.finalysis.chat.outbound;

import com.finalysis.ai.outbound.RedactionContext;
import com.finalysis.chat.tool.AccountAliases;
import com.finalysis.chat.tool.RefRegistry;
import com.finalysis.chat.tool.ToolScope;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What one turn knows about leaving the machine (docs/plans/phase-6.md §12): its aliases and refs, the user's own
 * identifiers (local only), the category names, and each merchant's decision, made once per turn and counted once per
 * name. It is also the tools' {@link ToolScope}: the names it issued resolve back to ids and stored merchants. Not
 * thread-safe: one per turn.
 */
public final class OutboundTurn implements ToolScope {

    private final AccountAliases aliases;
    private final RefRegistry refs = new RefRegistry();
    private final RedactionContext redaction;
    private final List<AccountIdentity> identities;
    private final Map<String, Long> categories;
    private final MerchantOutbound merchantOutbound;
    private final Map<String, MerchantOutbound.Decision> merchants = new HashMap<>();
    private int withheldMerchants;
    private int refusedMerchants;

    OutboundTurn(AccountAliases aliases, RedactionContext redaction, List<AccountIdentity> identities,
                 Map<String, Long> categories, MerchantOutbound merchantOutbound) {
        this.aliases = aliases;
        this.redaction = redaction;
        this.identities = List.copyOf(identities);
        this.categories = Map.copyOf(categories);
        this.merchantOutbound = merchantOutbound;
    }

    public AccountAliases aliases() {
        return aliases;
    }

    public RefRegistry refs() {
        return refs;
    }

    public RedactionContext redaction() {
        return redaction;
    }

    List<AccountIdentity> identities() {
        return identities;
    }

    /** Category name → id. */
    public Map<String, Long> categories() {
        return categories;
    }

    /** A stored merchant's outbound text; null stays null (the rows without a merchant). */
    public String merchant(String stored) {
        if (stored == null) {
            return null;
        }
        return merchants.computeIfAbsent(stored, m -> {
            MerchantOutbound.Decision decision = merchantOutbound.decide(m, redaction);
            switch (decision.outcome()) {
                case WITHHELD -> withheldMerchants++;
                case REFUSED -> refusedMerchants++;
                case SENT -> { }
            }
            return decision;
        }).text();
    }

    /** Distinct merchant names withheld so far in this turn (instruction-like or an amount in the name). */
    public int withheldMerchants() {
        return withheldMerchants;
    }

    /** Distinct merchant names refused so far (person-to-person, empty after redaction, or refused by the guard). */
    public int refusedMerchants() {
        return refusedMerchants;
    }

    @Override
    public Optional<Long> accountId(String alias) {
        return aliases.accountId(alias);
    }

    @Override
    public Optional<Long> categoryId(String name) {
        return Optional.ofNullable(categories.get(name));
    }

    @Override
    public boolean isWithheldMerchant(String text) {
        return MerchantOutbound.WITHHELD.equals(text);
    }

    /**
     * The stored merchants this turn sent as exactly this text (docs/plans/phase-6.md §4.3), from the decisions made
     * so far; step 6 adds the lookup for texts the turn hasn't shown.
     */
    @Override
    public List<String> storedMerchants(String outboundText) {
        return merchants.entrySet().stream()
                .filter(e -> e.getValue().outcome() == MerchantOutbound.Outcome.SENT
                        && e.getValue().text().equals(outboundText))
                .map(Map.Entry::getKey).sorted().toList();
    }

    boolean merchantMayGo(String text) {
        return merchantOutbound.mayGo(text, redaction);
    }
}

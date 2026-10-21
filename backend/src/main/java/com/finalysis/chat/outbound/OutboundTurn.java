package com.finalysis.chat.outbound;

import com.finalysis.ai.outbound.RedactionContext;
import com.finalysis.chat.tool.AccountAliases;
import com.finalysis.chat.tool.MerchantDirectory;
import com.finalysis.chat.tool.RefRegistry;
import com.finalysis.chat.tool.SearchText;
import com.finalysis.chat.tool.ToolScope;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

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
    private final Supplier<List<String>> storedMerchantNames;
    private final SearchTextOutbound search;
    private final Map<String, MerchantOutbound.Decision> merchants = new HashMap<>();
    private final Map<String, Optional<SearchText>> searches = new HashMap<>();
    private MerchantDirectory directory;
    private int withheldMerchants;
    private int refusedMerchants;

    /**
     * @param storedMerchantNames every distinct stored merchant, read when the turn first resolves a merchant text
     * @param search              prepares search texts; never called for a turn that doesn't search
     */
    OutboundTurn(AccountAliases aliases, RedactionContext redaction, List<AccountIdentity> identities,
                 Map<String, Long> categories, MerchantOutbound merchantOutbound,
                 Supplier<List<String>> storedMerchantNames, SearchTextOutbound search) {
        this.aliases = aliases;
        this.redaction = redaction;
        this.identities = List.copyOf(identities);
        this.categories = Map.copyOf(categories);
        this.merchantOutbound = merchantOutbound;
        this.storedMerchantNames = storedMerchantNames;
        this.search = search;
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
     * The stored merchants that go out as exactly this text (docs/plans/phase-6.md §4.3), from the turn's directory of
     * every merchant: a text the model saw in this turn, or one it knows from the question, resolves alike.
     */
    @Override
    public List<String> storedMerchants(String outboundText) {
        return directory().stored(outboundText);
    }

    /** Prepared once per text per turn, so a repeated search makes no second embedding call. */
    @Override
    public Optional<SearchText> searchText(String typed) {
        return searches.computeIfAbsent(typed, t -> search.prepare(t, this, directory()));
    }

    /**
     * Every stored merchant whose name may be sent, by its outbound text: decided once per turn, by the same rules as
     * {@link #merchant}, without counting (only the names actually sent are counted).
     */
    MerchantDirectory directory() {
        if (directory == null) {
            Map<String, String> sendable = new HashMap<>();
            for (String stored : storedMerchantNames.get()) {
                MerchantOutbound.Decision decision = merchants.containsKey(stored) ? merchants.get(stored)
                        : merchantOutbound.decide(stored, redaction);
                if (decision.outcome() == MerchantOutbound.Outcome.SENT) {
                    sendable.put(stored, decision.text());
                }
            }
            directory = new MerchantDirectory(sendable);
        }
        return directory;
    }

    boolean merchantMayGo(String text) {
        return merchantOutbound.mayGo(text, redaction);
    }
}

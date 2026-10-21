package com.finalysis.chat.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The turn's account aliases (docs/plans/phase-6.md §3.4): the account type and a number per type, in ascending account
 * id ({@code CHECKING-1}, {@code SAVINGS-1}, {@code CARD-1}, {@code CARD-2}). Accounts reach the model only as these; the
 * map back to ids stays here and is stored with the turn.
 */
public final class AccountAliases {

    private final Map<Long, Entry> byId = new LinkedHashMap<>();
    private final Map<String, Long> byAlias = new LinkedHashMap<>();

    /**
     * One account.
     *
     * @param type CHECKING, SAVINGS or CREDIT_CARD, as stored
     */
    public record Entry(long id, String alias, String type, boolean closed) {
    }

    /** One stored account, as read; {@link #of} numbers them. */
    public record Account(long id, String type, boolean closed) {
    }

    private AccountAliases() {
    }

    /** Aliases for these accounts, numbered per type in ascending id. */
    public static AccountAliases of(List<Account> accounts) {
        AccountAliases aliases = new AccountAliases();
        Map<String, Integer> next = new LinkedHashMap<>();
        accounts.stream().sorted((a, b) -> Long.compare(a.id(), b.id())).forEach(account -> {
            String prefix = account.type().equals("CREDIT_CARD") ? "CARD" : account.type();
            int number = next.merge(prefix, 1, Integer::sum);
            Entry entry = new Entry(account.id(), prefix + "-" + number, account.type(), account.closed());
            aliases.byId.put(account.id(), entry);
            aliases.byAlias.put(entry.alias(), account.id());
        });
        return aliases;
    }

    /** The alias of an account id; an id this turn doesn't know is a bug, never sent as a number. */
    public String alias(long accountId) {
        Entry entry = byId.get(accountId);
        if (entry == null) {
            throw new IllegalStateException("no alias for an account of this turn");
        }
        return entry.alias();
    }

    /** The alias of an optional account id, or null. */
    public String aliasOrNull(Long accountId) {
        return accountId == null ? null : alias(accountId);
    }

    /** The account behind an alias, if this turn issued it. */
    public Optional<Long> accountId(String alias) {
        return Optional.ofNullable(byAlias.get(alias));
    }

    public boolean isAlias(String text) {
        return byAlias.containsKey(text);
    }

    public List<Entry> entries() {
        return List.copyOf(byId.values());
    }

    /** Alias → account id, for the stored turn. */
    public Map<String, Long> asMap() {
        return Map.copyOf(byAlias);
    }
}

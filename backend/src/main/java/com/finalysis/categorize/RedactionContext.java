package com.finalysis.categorize;

import java.util.List;
import java.util.Set;

/**
 * The user's own identifiers that must never leave the machine (SEC-08, docs/plans/phase-4.md §5.2), read
 * locally before a run by {@link RedactionContextQuery}. Never logged.
 *
 * @param lastFours      every registered account's last four
 * @param accountPhrases every account name and institution name (they may contain a person's name)
 * @param counterparties every person-to-person counterparty (§2), which the guard refuses to send
 */
public record RedactionContext(Set<String> lastFours, List<String> accountPhrases, Set<String> counterparties) {

    public static final RedactionContext EMPTY = new RedactionContext(Set.of(), List.of(), Set.of());

    public RedactionContext {
        lastFours = Set.copyOf(lastFours);
        accountPhrases = List.copyOf(accountPhrases);
        counterparties = Set.copyOf(counterparties);
    }
}

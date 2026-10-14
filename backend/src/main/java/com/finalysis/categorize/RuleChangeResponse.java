package com.finalysis.categorize;

/**
 * Body of a rule create or update (docs/plans/phase-4.md §3.2): the rule, and what it does to the stored txns,
 * so a rule that never fires is visible. Counted as if the rule were active, so a deactivated rule shows
 * what reactivating it would do.
 *
 * @param matchCount txns the rule matches, whatever their current category
 * @param winCount   of those, the txns where it is the winning rule
 * @param shadowedBy the one higher-ranked rule that wins on every txn this rule matches; null if this rule
 *                   wins somewhere, matches nothing, or loses to more than one rule
 */
public record RuleChangeResponse(RuleResponse rule, int matchCount, int winCount, Long shadowedBy) {
}

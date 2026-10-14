package com.finalysis.categorize;

/**
 * Values of ck_categorization_rule_field; names must match the CHECK constraint exactly. What a rule's
 * pattern is matched against (docs/plans/phase-4.md §3.1).
 */
public enum RuleField {
    /** The cleaned merchant ({@code txn.merchant}); a txn without one matches no MERCHANT rule. */
    MERCHANT,
    /**
     * The raw description, normalized for matching only (wrapped lines joined, upper case, single spaces), for
     * words that cleaning removes or that span the whole line.
     */
    DESCRIPTION
}

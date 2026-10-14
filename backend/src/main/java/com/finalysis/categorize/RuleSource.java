package com.finalysis.categorize;

/**
 * Values of ck_categorization_rule_source; names must match the CHECK constraint exactly. {@code USER} rules
 * are written through the rules API, {@code USER_CORRECTION} rules by correcting a txn; both outrank seeds.
 */
public enum RuleSource {
    SEED, USER_CORRECTION, USER;

    /** User rules come before seeds whatever their priorities (CAT-04). */
    boolean isUser() {
        return this != SEED;
    }
}

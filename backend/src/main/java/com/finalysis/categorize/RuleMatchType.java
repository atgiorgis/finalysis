package com.finalysis.categorize;

/**
 * Values of ck_categorization_rule_match_type; names must match the CHECK constraint exactly. Declared from
 * most to least specific, the order that breaks ties between rules (docs/plans/phase-4.md §3.2).
 */
public enum RuleMatchType {
    EXACT, PREFIX, CONTAINS, REGEX
}

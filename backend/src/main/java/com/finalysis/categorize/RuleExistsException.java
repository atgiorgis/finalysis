package com.finalysis.categorize;

/**
 * An active user rule with the same field, match type, pattern and direction already exists
 * ({@code uq_categorization_rule_user_pattern}). Mapped to 409 {@code rule-exists} with its id, so the
 * client can change that rule instead.
 */
public class RuleExistsException extends RuntimeException {

    private final long ruleId;

    public RuleExistsException(long ruleId) {
        super("An active user rule with the same field, match type, pattern and direction exists");
        this.ruleId = ruleId;
    }

    public long getRuleId() {
        return ruleId;
    }
}

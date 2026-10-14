package com.finalysis.categorize;

/**
 * A change only user rules allow, asked of a seeded rule: seeds can be deactivated and reactivated, never
 * edited or deleted. Mapped to 409 {@code rule-seeded}.
 */
public class RuleSeededException extends RuntimeException {

    public RuleSeededException(String message) {
        super(message);
    }
}

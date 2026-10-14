package com.finalysis.categorize;

/**
 * A rule whose pattern can't be stored: empty or too long after normalization, or a regex that doesn't
 * compile. Mapped to 422 {@code rule-invalid} naming the field; the message never quotes the pattern.
 */
public class RuleInvalidException extends RuntimeException {

    private final String field;

    public RuleInvalidException(String field, String problem) {
        super(field + " " + problem);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}

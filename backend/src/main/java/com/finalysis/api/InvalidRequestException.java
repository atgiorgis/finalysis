package com.finalysis.api;

import java.util.List;

/** Request parameters that fail validation outside bean validation. Mapped to a 400 with {@code errors}. */
public class InvalidRequestException extends RuntimeException {

    private final List<InvalidField> errors;

    public InvalidRequestException(List<InvalidField> errors) {
        super("One or more fields are invalid");
        this.errors = List.copyOf(errors);
    }

    public List<InvalidField> getErrors() {
        return errors;
    }
}

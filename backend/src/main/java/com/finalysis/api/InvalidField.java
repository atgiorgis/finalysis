package com.finalysis.api;

/** One entry in the {@code errors} list of a 400 problem response. */
public record InvalidField(String field, String message) {
}

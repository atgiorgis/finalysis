package com.finalysis.analytics;

/** A category as analytics reports it. Where a row has none, it is null and the row is labelled by its bucket. */
public record CategoryRef(long id, String name, CategoryKind kind) {
}

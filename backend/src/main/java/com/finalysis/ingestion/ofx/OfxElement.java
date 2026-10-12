package com.finalysis.ingestion.ofx;

/**
 * A node of an OFX document: an {@link OfxAggregate} (has children) or an {@link OfxLeaf} (has a
 * value). {@code toString()} never includes values, so nodes are safe to log (SEC-10).
 */
public sealed interface OfxElement permits OfxAggregate, OfxLeaf {

    /** Tag name as written, e.g. {@code STMTTRN}. */
    String name();

    /** 1-based line of the opening tag in the file. */
    int line();
}

package com.finalysis.ingestion.ofx;

import java.util.Objects;

/**
 * A tag with a value, e.g. {@code <TRNAMT>-4.50}. See {@link OfxReader} for exactly which
 * characters the value contains.
 *
 * <p>An element with no content ({@code <MEMO></MEMO>}, or {@code <MEMO>} directly followed by a
 * closing tag) is a leaf with an empty value, even if its name is normally an aggregate; only the
 * root is always an aggregate. Callers should treat an empty value like a missing element.
 */
public record OfxLeaf(String name, String value, int line) implements OfxElement {

    public OfxLeaf {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
    }

    /** Name and line only: values are file contents (SEC-10). */
    @Override
    public String toString() {
        return "OfxLeaf[" + name + ", line " + line + "]";
    }
}

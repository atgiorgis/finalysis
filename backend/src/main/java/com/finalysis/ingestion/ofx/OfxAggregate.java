package com.finalysis.ingestion.ofx;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A tag with child elements, e.g. {@code <STMTTRN>...</STMTTRN>}. Children keep file order. */
public record OfxAggregate(String name, List<OfxElement> children, int line) implements OfxElement {

    public OfxAggregate {
        Objects.requireNonNull(name, "name");
        children = List.copyOf(children);
    }

    /** First child with the given name, if any. */
    public Optional<OfxElement> child(String name) {
        return children.stream().filter(child -> child.name().equals(name)).findFirst();
    }

    /** All child aggregates with the given name, in file order. */
    public List<OfxAggregate> aggregates(String name) {
        return children.stream()
                .filter(child -> child instanceof OfxAggregate && child.name().equals(name))
                .map(OfxAggregate.class::cast)
                .toList();
    }

    /** Value of the first child leaf with the given name, if any. */
    public Optional<String> leafValue(String name) {
        return children.stream()
                .filter(child -> child instanceof OfxLeaf && child.name().equals(name))
                .map(child -> ((OfxLeaf) child).value())
                .findFirst();
    }

    /** Name, line and child count only: values are file contents (SEC-10). */
    @Override
    public String toString() {
        return "OfxAggregate[" + name + ", line " + line + ", " + children.size() + " children]";
    }
}

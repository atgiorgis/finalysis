package com.finalysis.chat.tool;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * What one figure of a total cell rests on (docs/plans/phase-6.md §6): drill-downs of
 * {@code GET /api/analytics/transactions}, each with a sign. Recorded by the projection beside the value it describes,
 * so a citation never guesses which rows a figure came from.
 * <ul>
 *   <li>{@link Relation#SUM}: the figure, signed as the result returned it, equals the sum over the terms of
 *       {@code sign × component} of each drill-down's total ({@code CitationTest} checks it on the fixture);</li>
 *   <li>{@link Relation#DERIVED}: the figure is computed from these rows (a percent, an average, a count of pending
 *       reviews); the drill-downs show its inputs, and no sum is claimed.</li>
 * </ul>
 * Local only: the parameters hold ids.
 */
public record FigureBasis(Relation relation, Component component, List<Term> terms) {

    public enum Relation { SUM, DERIVED }

    /** Which part of a drill-down's {@code total} a sum uses. */
    public enum Component { NET, MONEY_OUT, MONEY_IN, COUNT }

    /** One drill-down and its sign in the sum. */
    public record Term(int sign, Map<String, String> drillDown) {

        public Term {
            if (sign != 1 && sign != -1) {
                throw new IllegalArgumentException("sign must be 1 or -1");
            }
            drillDown = Map.copyOf(drillDown);
        }

        public Term negated() {
            return new Term(-sign, drillDown);
        }
    }

    public FigureBasis {
        terms = List.copyOf(terms);
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("a figure rests on at least one drill-down");
        }
    }

    public static FigureBasis sum(Component component, List<Term> terms) {
        return new FigureBasis(Relation.SUM, component, terms);
    }

    /** One drill-down, added. */
    public static FigureBasis sum(Component component, Map<String, String> drillDown) {
        return sum(component, List.of(new Term(1, drillDown)));
    }

    /** Several drill-downs, each added. */
    @SafeVarargs
    public static FigureBasis sumOf(Component component, Map<String, String>... drillDowns) {
        return sum(component, Arrays.stream(drillDowns).map(d -> new Term(1, d)).toList());
    }

    @SafeVarargs
    public static FigureBasis derived(Map<String, String>... inputs) {
        return new FigureBasis(Relation.DERIVED, null,
                Arrays.stream(inputs).map(d -> new Term(1, d)).toList());
    }

    public static FigureBasis derived(List<Term> inputs) {
        return new FigureBasis(Relation.DERIVED, null, inputs);
    }
}

package com.finalysis.chat.eval;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * The search-floor sweep's arithmetic (docs/plans/phase-6.md §4.3, step 8), pure Java: given what
 * {@code search_transactions} returned for a query at the lowest floor, what it would return at each higher floor, and
 * whether the question's target merchants are among the candidates.
 *
 * <p>Why one search at the lowest floor is enough: the tool lists name matches first (no floor applies to them), then
 * meaning hits nearest first, and cuts the list at its limit. Raising the floor only removes meaning hits from the tail,
 * so the list at a higher floor is a prefix of the list at the lowest, and the limit cuts both alike. The one
 * approximation: similarities come back rounded to two decimals while the tool compares the unrounded value, so a hit
 * within 0.005 below a floor counts as at it.
 */
final class SearchSweep {

    /** 0.30 to 0.60 in steps of 0.05. */
    static final List<BigDecimal> FLOORS = IntStream.rangeClosed(0, 6)
            .mapToObj(i -> new BigDecimal("0.30").add(new BigDecimal("0.05").multiply(BigDecimal.valueOf(i))))
            .toList();

    private SearchSweep() {
    }

    /** A candidate as the tool returned it; {@code similarity} is null for a name match. */
    record Candidate(String merchant, String match, BigDecimal similarity) {

        boolean byName() {
            return similarity == null;
        }
    }

    /**
     * One query at one floor.
     *
     * @param candidates the merchants the search would return, in order
     * @param found      the targets some candidate's name contains
     * @param others     candidates that contain no target: what the model must read past
     */
    record AtFloor(BigDecimal floor, List<String> candidates, List<String> found, int others) {

        boolean allFound(List<String> targets) {
            return found.size() == targets.size();
        }
    }

    /** One query of a question, at every floor. */
    record QueryResult(String question, String query, List<String> targets, String status,
                       List<Candidate> candidates, List<AtFloor> floors) {
    }

    /**
     * Totals at one floor over every query.
     *
     * @param queriesAllFound   queries whose candidates hold every target
     * @param questionsAllFound questions where at least one query holds every target
     */
    record FloorSummary(BigDecimal floor, int queries, int queriesAllFound, int questions, int questionsAllFound,
                        int targets, int targetsFound, int candidates, int others) {
    }

    static AtFloor at(BigDecimal floor, List<Candidate> candidates, List<String> targets) {
        List<String> kept = candidates.stream()
                .filter(c -> c.byName() || c.similarity().compareTo(floor) >= 0)
                .map(Candidate::merchant).toList();
        List<String> found = targets.stream().filter(t -> kept.stream().anyMatch(m -> contains(m, t))).toList();
        int others = (int) kept.stream().filter(m -> targets.stream().noneMatch(t -> contains(m, t))).count();
        return new AtFloor(floor, kept, found, others);
    }

    static QueryResult query(String question, String query, List<String> targets, String status,
                             List<Candidate> candidates) {
        return new QueryResult(question, query, targets, status, candidates,
                FLOORS.stream().map(f -> at(f, candidates, targets)).toList());
    }

    static List<FloorSummary> summarize(List<QueryResult> results) {
        List<String> questions = results.stream().map(QueryResult::question).distinct().toList();
        List<FloorSummary> summaries = new ArrayList<>();
        for (int i = 0; i < FLOORS.size(); i++) {
            int index = i;
            List<AtFloor> at = results.stream().map(r -> r.floors().get(index)).toList();
            int queriesAllFound = (int) IntStream.range(0, results.size())
                    .filter(q -> at.get(q).allFound(results.get(q).targets())).count();
            int questionsAllFound = (int) questions.stream().filter(question -> IntStream.range(0, results.size())
                    .anyMatch(q -> results.get(q).question().equals(question)
                            && at.get(q).allFound(results.get(q).targets()))).count();
            summaries.add(new FloorSummary(FLOORS.get(i), results.size(), queriesAllFound, questions.size(),
                    questionsAllFound, results.stream().mapToInt(r -> r.targets().size()).sum(),
                    at.stream().mapToInt(a -> a.found().size()).sum(),
                    at.stream().mapToInt(a -> a.candidates().size()).sum(),
                    at.stream().mapToInt(AtFloor::others).sum()));
        }
        return summaries;
    }

    /**
     * The highest floor at which every query that finds all its targets at the lowest floor still finds them: above it,
     * some target the search could reach is lost. Null when no query finds its targets at all.
     */
    static BigDecimal highestLossless(List<QueryResult> results) {
        List<QueryResult> reachable = results.stream()
                .filter(r -> r.floors().getFirst().allFound(r.targets())).toList();
        if (reachable.isEmpty()) {
            return null;
        }
        BigDecimal best = FLOORS.getFirst();
        for (int i = 1; i < FLOORS.size(); i++) {
            int index = i;
            if (reachable.stream().allMatch(r -> r.floors().get(index).allFound(r.targets()))) {
                best = FLOORS.get(i);
            } else {
                break;
            }
        }
        return best;
    }

    private static boolean contains(String merchant, String target) {
        return merchant.toUpperCase(Locale.ROOT).contains(target.toUpperCase(Locale.ROOT));
    }
}

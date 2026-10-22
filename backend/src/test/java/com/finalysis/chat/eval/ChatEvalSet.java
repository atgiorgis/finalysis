package com.finalysis.chat.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * One chat question set ({@code sample-data/chat/<set>.yml}, docs/plans/phase-6.md §13.1, step 1 notes), typed for the
 * evaluation (step 8). Only what scoring needs is kept: the asks, the expectations and the per-dataset "today". The
 * figures' sources are not: {@code ChatQuestionSetsTest} has checked every figure against them, and the harness only
 * compares answers with the values.
 *
 * @param sha256      of the file's bytes, recorded with the results
 * @param userWritten the owner's own question ids (empty for dev)
 */
record ChatEvalSet(String name, int version, String sha256, Map<String, Dataset> datasets, List<String> userWritten,
                   List<Question> questions) {

    ChatEvalSet {
        datasets = Collections.unmodifiableMap(new LinkedHashMap<>(datasets));
        userWritten = List.copyOf(userWritten);
        questions = List.copyOf(questions);
    }

    /** A dataset a question runs on, and the day the conversation believes it is. */
    record Dataset(String name, LocalDate today) {
    }

    record Question(String id, String author, String category, String dataset, List<String> searchTargets,
                    List<Turn> turns) {

        Question {
            searchTargets = List.copyOf(searchTargets);
            turns = List.copyOf(turns);
        }
    }

    enum Expect { ANSWER, REFUSAL }

    /**
     * One turn's expectations.
     *
     * @param tools     alternatives: the turn's calls must include every tool of one (an empty one needs no tool)
     * @param figures   alternatives: a correct answer has every figure of one
     * @param forbidden figures that must not appear
     * @param citations rows that must be among the citations
     * @param says      phrase check (step 8d), optional: regular expressions, case ignored; an answer turn is correct
     *                  only if at least one matches the shown answer
     * @param neverSays regular expressions, case ignored, none of which may match the shown answer
     */
    record Turn(String ask, Expect expect, List<Set<String>> tools, List<List<ExpectedFigure>> figures,
                List<ExpectedFigure> forbidden, List<Locator> citations, String note, boolean dependsOnPrevious,
                List<Pattern> says, List<Pattern> neverSays) {

        Turn {
            tools = tools.stream().map(Set::copyOf).toList();
            figures = figures.stream().map(List::copyOf).toList();
            forbidden = List.copyOf(forbidden);
            citations = List.copyOf(citations);
            says = List.copyOf(says);
            neverSays = List.copyOf(neverSays);
        }

        /** A turn without phrase checks. */
        Turn(String ask, Expect expect, List<Set<String>> tools, List<List<ExpectedFigure>> figures,
             List<ExpectedFigure> forbidden, List<Locator> citations, String note, boolean dependsOnPrevious) {
            this(ask, expect, tools, figures, forbidden, citations, note, dependsOnPrevious, List.of(), List.of());
        }
    }

    /** A phrase check's expression, case and Unicode case ignored. */
    static Pattern phrase(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** A figure's kind follows its written form (the files' header): two decimals, one decimal, or none. */
    enum FigureKind { MONEY, PERCENT, COUNT }

    /** A magnitude as the set writes it ({@code 190.10}, {@code 9.4}, {@code 18}). */
    record ExpectedFigure(String text, BigDecimal value, FigureKind kind) {

        private static final Pattern MONEY = Pattern.compile("\\d+\\.\\d{2}");
        private static final Pattern PERCENT = Pattern.compile("\\d+\\.\\d");
        private static final Pattern COUNT = Pattern.compile("\\d+");

        static ExpectedFigure parse(String text) {
            FigureKind kind;
            if (MONEY.matcher(text).matches()) {
                kind = FigureKind.MONEY;
            } else if (PERCENT.matcher(text).matches()) {
                kind = FigureKind.PERCENT;
            } else if (COUNT.matcher(text).matches()) {
                kind = FigureKind.COUNT;
            } else {
                throw new IllegalArgumentException("A figure is a magnitude with 2, 1 or 0 decimals: " + text);
            }
            return new ExpectedFigure(text, new BigDecimal(text), kind);
        }
    }

    /** A row by words of its description, and its account slug and date when given (either may be null). */
    record Locator(String account, LocalDate date, String contains) {
    }

    Question question(String id) {
        return questions.stream().filter(q -> q.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No question " + id));
    }

    boolean userWritten(Question question) {
        return userWritten.contains(question.id()) || "user".equals(question.author());
    }

    // ------------------------------------------------------------------ loading

    static ChatEvalSet load(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(new String(bytes, StandardCharsets.UTF_8));
        Map<String, Dataset> datasets = new LinkedHashMap<>();
        map(root.get("datasets")).forEach((name, value) -> datasets.put(name,
                new Dataset(name, LocalDate.parse(string(map(value).get("today"))))));
        List<Question> questions = new ArrayList<>();
        for (Object q : list(root.get("questions"))) {
            Map<String, Object> question = map(q);
            List<Turn> turns = new ArrayList<>();
            for (Object t : list(question.get("turns"))) {
                turns.add(turn(map(t)));
            }
            String dataset = string(question.get("dataset"));
            if (!datasets.containsKey(dataset)) {
                throw new IllegalArgumentException("Question " + question.get("id") + " names no known dataset");
            }
            questions.add(new Question(string(question.get("id")), string(question.get("author")),
                    string(question.get("category")), dataset, strings(question.get("searchTargets")), turns));
        }
        return new ChatEvalSet(string(root.get("set")), ((Number) root.get("version")).intValue(), sha256(bytes),
                datasets, strings(root.get("userWritten")), questions);
    }

    private static Turn turn(Map<String, Object> turn) {
        List<Set<String>> tools = new ArrayList<>();
        for (Object alternative : list(turn.get("tools"))) {
            tools.add(new LinkedHashSet<>(strings(alternative)));
        }
        List<List<ExpectedFigure>> figures = new ArrayList<>();
        for (Object alternative : list(turn.get("figures"))) {
            figures.add(list(alternative).stream()
                    .map(figure -> ExpectedFigure.parse(string(map(figure).get("value")))).toList());
        }
        List<ExpectedFigure> forbidden = strings(turn.get("forbidden")).stream().map(ExpectedFigure::parse).toList();
        List<Locator> citations = list(turn.get("citations")).stream().map(ChatEvalSet::map)
                .map(c -> new Locator(string(c.get("account")),
                        c.get("date") == null ? null : LocalDate.parse(string(c.get("date"))),
                        string(c.get("contains"))))
                .toList();
        Expect expect = Expect.valueOf(string(turn.get("expect")));
        List<Pattern> says = strings(turn.get("says")).stream().map(ChatEvalSet::phrase).toList();
        List<Pattern> neverSays = strings(turn.get("neverSays")).stream().map(ChatEvalSet::phrase).toList();
        if (expect == Expect.REFUSAL && !says.isEmpty()) {
            throw new IllegalArgumentException("A refusal turn takes no says: " + turn.get("ask"));
        }
        return new Turn(string(turn.get("ask")), expect, tools, figures, forbidden, citations,
                string(turn.get("note")), Boolean.TRUE.equals(turn.get("dependsOnPrevious")), says, neverSays);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }

    private static List<?> list(Object value) {
        return value == null ? List.of() : (List<?>) value;
    }

    private static List<String> strings(Object value) {
        return list(value).stream().map(String::valueOf).toList();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

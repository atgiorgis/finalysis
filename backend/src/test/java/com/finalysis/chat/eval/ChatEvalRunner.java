package com.finalysis.chat.eval;

import com.finalysis.chat.AnswerSource;
import com.finalysis.chat.ChatPrompt;
import com.finalysis.chat.ChatModelClient;
import com.finalysis.chat.ChatService;
import com.finalysis.chat.ChatTurnResponse;
import com.finalysis.chat.citation.Citation;
import com.finalysis.chat.citation.Citation.CitedTxn;
import com.finalysis.chat.eval.ChatCallRecorder.TurnCalls;
import com.finalysis.chat.eval.ChatEvalResults.IndexBuild;
import com.finalysis.chat.eval.ChatEvalResults.Observation;
import com.finalysis.chat.eval.ChatEvalResults.QuestionResult;
import com.finalysis.chat.eval.ChatEvalResults.Run;
import com.finalysis.chat.eval.ChatEvalResults.ToolUse;
import com.finalysis.chat.eval.ChatEvalResults.TurnResult;
import com.finalysis.chat.eval.ChatEvalScoring.Shown;
import com.finalysis.chat.eval.ChatEvalScoring.TurnScore;
import com.finalysis.chat.eval.ChatEvalSet.Locator;
import com.finalysis.chat.eval.ChatEvalSet.Question;
import com.finalysis.chat.eval.ChatEvalSet.Turn;
import com.finalysis.support.MutableClock;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.ErrorResponseException;

/**
 * Runs a chat question set against the context's chat model (docs/plans/phase-6.md §13.2, step 8): per dataset, a
 * fresh seed and search index, "today" pinned to the set's date for that dataset, then every question in a new
 * conversation through {@link ChatService} (the API's own path: redaction, guard, limits, grounding, citations), a
 * dialogue's turns in one. Each turn is scored as it ends ({@link ChatEvalScoring}).
 *
 * <p>Two stops: the run budget ({@code -Deval.max-cost}, dollars, summed from the turns' estimates), which ends the run
 * with what was answered and says so; and {@value #MAX_CONSECUTIVE_FAILURES} failed turns in a row (expired credentials,
 * a missing model grant), which aborts with nothing written, since nothing was evaluated.
 */
class ChatEvalRunner {

    static final int MAX_CONSECUTIVE_FAILURES = 3;
    static final List<String> SETTING_PREFIXES = List.of("finalysis.chat.", "finalysis.ai.",
            "spring.ai.bedrock.converse.chat.options.", "spring.ai.bedrock.titan.embedding.",
            "spring.ai.bedrock.aws.region", "spring.ai.bedrock.aws.timeout", "spring.ai.model.");

    private final ChatService chat;
    private final ChatModelClient client;
    private final ChatEvalSeeder seeder;
    private final ChatCallRecorder recorder;
    private final MutableClock clock;
    private final JdbcTemplate jdbc;
    private final ConfigurableEnvironment environment;

    ChatEvalRunner(ChatService chat, ChatModelClient client, ChatEvalSeeder seeder, ChatCallRecorder recorder,
                   MutableClock clock, JdbcTemplate jdbc, ConfigurableEnvironment environment) {
        this.chat = chat;
        this.client = client;
        this.seeder = seeder;
        this.recorder = recorder;
        this.clock = clock;
        this.jdbc = jdbc;
        this.environment = environment;
    }

    /** Thrown when the run can't evaluate anything meaningful; no results are written. */
    static final class RunAbortedException extends IllegalStateException {

        RunAbortedException(String message) {
            super(message);
        }
    }

    Run run(ChatEvalSet set, String model, ChatEvalPrices prices, BigDecimal maxCost) throws Exception {
        long start = System.nanoTime();
        List<QuestionResult> results = new ArrayList<>();
        List<IndexBuild> index = new ArrayList<>();
        Map<String, String> today = new LinkedHashMap<>();
        BigDecimal spent = BigDecimal.ZERO;
        BigDecimal ledger = BigDecimal.ZERO;
        String stopped = null;
        int failuresInARow = 0;

        runs:
        for (ChatEvalSet.Dataset dataset : set.datasets().values()) {
            List<Question> questions = set.questions().stream()
                    .filter(q -> q.dataset().equals(dataset.name())).toList();
            if (questions.isEmpty()) {
                continue;
            }
            ChatEvalSeeder.Seeded seeded = seeder.seed(dataset.name());
            index.add(seeded.index());
            today.put(dataset.name(), dataset.today().toString());
            clock.set(dataset.today().atTime(12, 0));
            for (Question question : questions) {
                if (spent.compareTo(maxCost) >= 0) {
                    stopped = "run budget reached: $" + spent.toPlainString() + " of $" + maxCost.toPlainString()
                            + " (-Deval.max-cost) before " + question.id();
                    ledger = ledger.add(ledgerCost());
                    break runs;
                }
                QuestionResult result = ask(set, question, seeded);
                results.add(result);
                for (TurnResult turn : result.turns()) {
                    spent = spent.add(Objects.requireNonNullElse(turn.observed().cost(), BigDecimal.ZERO));
                    failuresInARow = turn.observed().answered() ? 0 : failuresInARow + 1;
                    if (failuresInARow >= MAX_CONSECUTIVE_FAILURES) {
                        throw new RunAbortedException(failuresInARow + " turns in a row failed (last: "
                                + turn.observed().problem() + ", at " + question.id() + "); no results written. "
                                + "Check the AWS profile's session and the model's access, then run again.");
                    }
                }
            }
            ledger = ledger.add(ledgerCost());
        }
        seeder.clear();
        return new Run(set.name(), set.sha256(), set.version(), model, client.promptCaching().name(),
                ChatPrompt.VERSION,
                ChatEvalSet.sha256(resource("chat/system-prompt.txt")), prices.source(), today, settings(environment),
                index, results, ledger, stopped, (System.nanoTime() - start) / 1_000_000);
    }

    private QuestionResult ask(ChatEvalSet set, Question question, ChatEvalSeeder.Seeded seeded) {
        long conversation = chat.create().id();
        List<Shown> shown = new ArrayList<>();
        List<Observation> observed = new ArrayList<>();
        for (Turn turn : question.turns()) {
            Observation observation = turn(conversation, turn, seeded);
            observed.add(observation);
            shown.add(new Shown(observation.answer(), observation.source(), observation.tools().stream()
                    .map(ToolUse::name).filter(Objects::nonNull).collect(Collectors.toSet()),
                    observation.citationsFound()));
            if (!observation.answered()) {
                break; // a dialogue's later turns depend on this one
            }
        }
        List<TurnScore> scores = ChatEvalScoring.score(question, shown, seeded.labels());
        List<TurnResult> turns = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) {
            Turn turn = question.turns().get(i);
            turns.add(new TurnResult(i + 1, turn.ask(), turn.expect(), turn.note(), observed.get(i), scores.get(i)));
        }
        return new QuestionResult(question.id(), question.category(), question.author(), set.userWritten(question),
                question.dataset(), question.turns().size(), turns);
    }

    private Observation turn(long conversation, Turn turn, ChatEvalSeeder.Seeded seeded) {
        recorder.begin();
        long start = System.nanoTime();
        ChatTurnResponse response;
        try {
            response = chat.ask(conversation, turn.ask());
        } catch (ErrorResponseException e) {
            TurnCalls calls = recorder.end();
            String type = e.getBody().getType() == null ? String.valueOf(e.getStatusCode().value())
                    : e.getBody().getType().toString().replace("urn:finalysis:problem:", "");
            return new Observation(null, type, AnswerSource.ERROR, null, false, 0, 0, List.of(), null, calls.calls(),
                    null, null, 0, 0, 0, null, millis(start), calls.retryFigures(), calls.rejectedAnswer(),
                    calls.searchQueries(), null, calls.invalidArguments());
        }
        long millis = millis(start);
        TurnCalls calls = recorder.end();
        ChatTurnResponse.Usage usage = response.usage();
        return new Observation(response.answer(), null, response.answerSource(), response.grounding().status(),
                response.grounding().retried(), response.grounding().figuresChecked(),
                response.grounding().ungrounded(),
                response.tools().stream().map(t -> new ToolUse(t.name(), t.status().name(), t.rows())).toList(),
                response.limitHit(), usage.rounds(), usage.inputTokens(), usage.outputTokens(),
                Objects.requireNonNullElse(usage.cacheReadTokens(), 0),
                Objects.requireNonNullElse(usage.cacheWriteTokens(), 0), usage.callsWithoutUsage(), usage.estimatedCost() == null ? null : new BigDecimal(usage.estimatedCost()),
                millis, calls.retryFigures(), calls.rejectedAnswer(), calls.searchQueries(),
                citationsFound(turn.citations(), response.citations(), seeded.accounts()), calls.invalidArguments());
    }

    /** Whether every expected row is among the turn's cited transactions; null when none is expected. */
    static Boolean citationsFound(List<Locator> expected, List<Citation> citations, Map<String, Long> accounts) {
        if (expected.isEmpty()) {
            return null;
        }
        List<CitedTxn> cited = citations.stream().flatMap(ChatEvalRunner::txns).toList();
        return expected.stream().allMatch(locator -> cited.stream().anyMatch(txn ->
                txn.status() == CitedTxn.Status.FOUND
                        && (locator.account() == null
                                || Objects.equals(txn.accountId(), accounts.get(locator.account())))
                        && (locator.date() == null || locator.date().equals(txn.txnDate()))
                        && text(txn).contains(locator.contains().toUpperCase(Locale.ROOT))));
    }

    private static Stream<CitedTxn> txns(Citation citation) {
        return switch (citation) {
            case Citation.Txn t -> Stream.of(t.txn());
            case Citation.Alert a -> a.txns().stream();
            case Citation.Total t -> Stream.concat(t.drillDowns().stream(),
                    t.figures().stream().flatMap(f -> f.terms().stream()).map(Citation.Term::drillDown))
                    .flatMap(d -> d.sampleTxns().stream());
            case Citation.Series s -> s.drillDown().sampleTxns().stream();
        };
    }

    private static String text(CitedTxn txn) {
        String line = txn.sourceLine() == null || txn.sourceLine().text() == null ? "" : txn.sourceLine().text();
        return (line + " " + Objects.requireNonNullElse(txn.merchant(), "")).toUpperCase(Locale.ROOT);
    }

    private BigDecimal ledgerCost() {
        return jdbc.queryForObject("SELECT coalesce(sum(estimated_cost), 0) FROM chat_usage", BigDecimal.class);
    }

    /** Every chat and AI setting of the context, by name; never anything that names a credential or a profile. */
    static Map<String, String> settings(ConfigurableEnvironment environment) {
        Set<String> names = new LinkedHashSet<>();
        for (var source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (SETTING_PREFIXES.stream().anyMatch(name::startsWith) && Stream.of("profile", "access-key",
                            "secret", "session-token", "credential", "password").noneMatch(lower::contains)) {
                        names.add(name);
                    }
                }
            }
        }
        Map<String, String> settings = new TreeMap<>();
        for (String name : names) {
            settings.put(name, String.valueOf(environment.getProperty(name)));
        }
        return settings;
    }

    private static byte[] resource(String name) {
        try (InputStream in = ChatEvalRunner.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is missing");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long millis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}

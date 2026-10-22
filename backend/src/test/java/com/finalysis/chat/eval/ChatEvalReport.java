package com.finalysis.chat.eval;

import com.finalysis.chat.eval.ChatEvalMetrics.Totals;
import com.finalysis.chat.eval.ChatEvalResults.IndexBuild;
import com.finalysis.chat.eval.ChatEvalResults.Observation;
import com.finalysis.chat.eval.ChatEvalResults.QuestionResult;
import com.finalysis.chat.eval.ChatEvalResults.Run;
import com.finalysis.chat.eval.ChatEvalResults.TurnResult;
import com.finalysis.chat.eval.ChatEvalSet.ExpectedFigure;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * One chat evaluation's results (docs/plans/phase-6.md §13.2, §13.3, step 8), written to {@code docs/eval/} as
 * {@code chat-<set>-<commit12>-<timestamp>.json} (everything, turn by turn) and {@code .md} (the tables, the owner's
 * own questions in their own section, then every answer as shown, for reading).
 *
 * @param commit    the full commit hash, or {@code unknown}
 * @param dirty     the working tree had changes (holdout runs refuse that)
 * @param startedAt ISO-8601, UTC
 */
record ChatEvalReport(String commit, boolean dirty, String startedAt, Run run, ChatEvalMetrics metrics,
                      Map<String, ExpectedTurns> expected) {

    static final String PREFIX = "chat-";
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final String NA = "n/a";

    /** What each turn of a question expected, as text, for the answers section. */
    record ExpectedTurns(List<String> turns) {
    }

    static ChatEvalReport of(ChatEvalGuards.GitState git, Instant startedAt, ChatEvalSet set, Run run) {
        List<String> categories = set.questions().stream().map(ChatEvalSet.Question::category).distinct().toList();
        Map<String, ExpectedTurns> expected = set.questions().stream().collect(Collectors.toMap(
                ChatEvalSet.Question::id, q -> new ExpectedTurns(q.turns().stream().map(ChatEvalReport::expected)
                        .toList()), (a, b) -> a, LinkedHashMap::new));
        return new ChatEvalReport(git.commit(), git.dirty(), startedAt.toString(), run,
                ChatEvalMetrics.of(run.questions(), categories), expected);
    }

    static String expected(ChatEvalSet.Turn turn) {
        if (turn.expect() == ChatEvalSet.Expect.REFUSAL) {
            return "a refusal, no figure";
        }
        String figures = turn.figures().isEmpty() ? "no figure required"
                : turn.figures().stream().map(a -> a.stream().map(ExpectedFigure::text)
                        .collect(Collectors.joining(" and "))).collect(Collectors.joining(" | "));
        String forbidden = turn.forbidden().isEmpty() ? ""
                : "; never " + turn.forbidden().stream().map(ExpectedFigure::text).collect(Collectors.joining(", "));
        return figures + forbidden;
    }

    /** {@code chat-<set>-<first 12 of the commit>-<yyyyMMddTHHmmssZ>}: what both files are named. */
    String fileStem() {
        String commit12 = commit.length() > 12 ? commit.substring(0, 12) : commit;
        return PREFIX + run.set() + "-" + commit12 + "-" + STAMP.format(Instant.parse(startedAt));
    }

    /** Writes the JSON and Markdown files; refuses to overwrite. Returns their paths, JSON first. */
    List<Path> write(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path json = dir.resolve(fileStem() + ".json");
        Path markdown = dir.resolve(fileStem() + ".md");
        if (Files.exists(json) || Files.exists(markdown)) {
            throw new IllegalStateException(fileStem() + " already exists in " + dir);
        }
        Files.writeString(json, JSON.writeValueAsString(this) + "\n", StandardCharsets.UTF_8);
        Files.writeString(markdown, markdown(), StandardCharsets.UTF_8);
        return List.of(json, markdown);
    }

    String consoleSummary() {
        return "Chat eval: " + run.set() + ", model " + run.model() + " (commit " + commit + (dirty ? ", dirty" : "")
                + ")\nPrompt caching: " + caching() + "\nTurn cost limit: $" + run.maxTurnCost()
                + (run.maxTurnCostOverridden() ? " (-Deval.maxTurnCost)" : "") + "\n\n" + summaryTable() + (run.stopped() == null ? "" : "\nStopped early: " + run.stopped() + "\n");
    }

    String markdown() {
        StringBuilder md = new StringBuilder();
        md.append("# Chat eval: ").append(run.set()).append(", ").append(run.model()).append("\n\n");
        md.append("- Set: `").append(run.set()).append("` version ").append(run.setVersion()).append(", SHA-256 `")
                .append(run.setSha256()).append("`, ").append(run.questions().size()).append(" questions run\n");
        md.append("- Commit: `").append(commit).append("`").append(dirty ? " (working tree dirty)" : " (clean)")
                .append("\n");
        md.append("- Started: ").append(startedAt).append("; wall time ").append(seconds(run.wallMillis()))
                .append(" s\n");
        md.append("- Chat model: `").append(run.model()).append("`; prompt `").append(run.promptVersion())
                .append("` (system prompt SHA-256 `").append(run.systemPromptSha256()).append("`)\n");
        md.append("- Today by dataset: ").append(run.todayByDataset().entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "))).append("\n");
        md.append("- Prices: ").append(run.pricesSource()).append("\n");
        md.append("- Prompt caching: ").append(caching()).append("\n");
        md.append("- Turn cost limit: $").append(run.maxTurnCost()).append(run.maxTurnCostOverridden()
                ? " (-Deval.maxTurnCost, this run only; the app's setting is unchanged)" : " (the app's setting)")
                .append("\n");
        if (run.stopped() != null) {
            md.append("- **Stopped early:** ").append(run.stopped()).append("\n");
        }
        md.append("\n");
        md.append("Scoring is deterministic (no model judges): an answer turn is correct when every figure of one "
                + "listed alternative appears in the shown answer by the grounding rule (exact, or whole dollars and "
                + "percents rounded half-even) and no forbidden figure does; a refusal turn when the answer has no "
                + "figure and refuses; a turn with phrase checks also needs one of its says expressions and none of "
                + "its neverSays. A turn a limit stopped is never a correct refusal, nor correct at all without tool "
                + "data (limit-stopped). A question is correct when all of its turns are. Every figure of a shown answer "
                + "is grounded: the model's answer passed the check or the Java fallback was shown; correct turns "
                + "answered by a fallback are counted apart. Costs are the app's estimates from the price file; a call "
                + "without usage is counted at its worst case.\n\n");
        md.append("## Summary\n\n").append(summaryTable()).append("\n");
        md.append("## By category\n\n").append(categoryTable()).append("\n");
        md.append("## Your questions\n\n").append(userSection()).append("\n");
        md.append("## Grounding, refusals, tools\n\n").append(groundingTable()).append("\n");
        md.append("## Invalid tool arguments\n\n").append(invalidTable()).append("\n");
        md.append("## Cost and latency\n\n").append(usageTable()).append("\n");
        md.append("## Per question\n\n").append(questionTable(run.questions())).append("\n");
        md.append("## Wrong turns\n\n").append(wrongTurns()).append("\n");
        md.append("## Answers\n\nEvery turn as shown, with its citation markers. A retried turn also shows the first "
                + "answer, which the user never saw.\n\n");
        run.questions().forEach(q -> md.append(answers(q)));
        md.append("## Search index\n\n| Dataset | Status | Merchants | Embedded | Already indexed | Calls "
                + "| Left over |\n|---|---|---:|---:|---:|---:|---:|\n");
        for (IndexBuild index : run.index()) {
            md.append("| ").append(index.dataset()).append(" | ").append(index.status()).append(" | ")
                    .append(index.merchants()).append(" | ").append(index.embedded()).append(" | ")
                    .append(index.cached()).append(" | ").append(index.calls()).append(" | ")
                    .append(index.remaining()).append(" |\n");
        }
        md.append("\n## Settings\n\n");
        run.settings().forEach((key, value) -> md.append("- `").append(key).append("`: `").append(value)
                .append("`\n"));
        return md.toString();
    }

    private String summaryTable() {
        Totals all = metrics.all();
        ChatEvalMetrics.Grounding g = metrics.grounding();
        return "| Questions correct | Turns correct | of which fallbacks | First-try grounded | Model answer shown "
                + "| Fallbacks shown | Failed | Tool choice | Limits reached | Limit-stopped | Bare-year retries "
                + "| Cost (USD) | Mean turn (s) |\n|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n"
                + "| " + all.questionsCorrect() + " / " + all.questions() + " (" + pct(all.questionsCorrectPct()) + ")"
                + " | " + all.turnsCorrect() + " / " + all.turns() + " (" + pct(all.turnsCorrectPct()) + ")"
                + " | " + all.fallbacksCorrect()
                + " | " + g.firstTry() + " / " + g.answered() + " (" + pct(g.firstTryPct()) + ")"
                + " | " + g.finalModel() + " / " + g.answered() + " (" + pct(g.finalModelPct()) + ")"
                + " | " + g.fallbacks() + " | " + g.failed()
                + " | " + all.toolChoice() + " (" + pct(all.toolChoicePct()) + ")"
                + " | " + metrics.limits().values().stream().mapToInt(Integer::intValue).sum()
                + " | " + metrics.limitStopped()
                + " | " + g.bareYearRetries() + " of " + g.retries() + " retries"
                + " | " + money(metrics.usage().cost())
                + " | " + seconds(metrics.latency().meanTurnMillis()) + " |\n";
    }

    private String categoryTable() {
        StringBuilder table = new StringBuilder("| Category | Questions correct | Turns correct | Tool choice |\n"
                + "|---|---:|---:|---:|\n");
        metrics.byCategory().forEach((category, t) -> table.append("| ").append(category).append(" | ")
                .append(t.questionsCorrect()).append(" / ").append(t.questions()).append(" | ")
                .append(t.turnsCorrect()).append(" / ").append(t.turns()).append(" | ")
                .append(t.toolChoice()).append(" |\n"));
        return table.toString();
    }

    private String userSection() {
        List<QuestionResult> user = run.questions().stream().filter(QuestionResult::userWritten).toList();
        if (user.isEmpty()) {
            return "This set has no user-written questions.\n";
        }
        Totals t = metrics.userWritten();
        StringBuilder md = new StringBuilder("Your ").append(t.questions()).append(" questions: ")
                .append(t.questionsCorrect()).append(" correct (").append(pct(t.questionsCorrectPct()))
                .append("), turns ").append(t.turnsCorrect()).append(" / ").append(t.turns()).append(".\n\n");
        md.append(questionTable(user)).append("\n");
        user.forEach(q -> md.append(answers(q)));
        return md.toString();
    }

    private String groundingTable() {
        ChatEvalMetrics.Grounding g = metrics.grounding();
        ChatEvalMetrics.Refusals r = metrics.refusals();
        return "| Measure | Value |\n|---|---:|\n"
                + "| Turns answered | " + g.answered() + " |\n"
                + "| Grounded on the first try | " + g.firstTry() + " (" + pct(g.firstTryPct()) + ") |\n"
                + "| Model answer shown (after the retry or not) | " + g.finalModel() + " (" + pct(g.finalModelPct())
                + ") |\n"
                + "| Java fallback shown | " + g.fallbacks() + " |\n"
                + "| Failed turns (no answer) | " + g.failed() + " |\n"
                + "| Grounding retries | " + g.retries() + " |\n"
                + "| Retries with a bare year among their figures | " + g.bareYearRetries() + " |\n"
                + "| Retries caused by bare years alone | " + g.onlyBareYears() + " |\n"
                + "| Refusals expected / detected / both | " + r.expected() + " / " + r.detected() + " / "
                + r.correct() + " |\n"
                + "| Refusal precision / recall | " + pct(r.precisionPct()) + " / " + pct(r.recallPct()) + " |\n"
                + "| False refusals (figures expected) | " + r.falseRefusals() + " |\n"
                + "| Injection questions correct | " + metrics.injection().correct() + " / "
                + metrics.injection().questions() + " |\n"
                + "| Turns showing a forbidden figure | " + metrics.injection().forbiddenShown() + " |\n"
                + "| Expected citations present | " + metrics.citations().found() + " / "
                + metrics.citations().expected() + " |\n"
                + "| Limits reached | " + (metrics.limits().isEmpty() ? "none" : metrics.limits().entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "))) + " |\n";
    }

    private String usageTable() {
        ChatEvalMetrics.Usage u = metrics.usage();
        ChatEvalMetrics.Latency l = metrics.latency();
        return "| Measure | Value |\n|---|---:|\n"
                + "| Model rounds | " + u.rounds() + " |\n"
                + "| Tool calls | " + u.toolCalls() + " |\n"
                + "| Prompt caching | " + run.promptCaching() + " |\n"
                + "| Input tokens: uncached / cache read / cache write | " + u.inputTokens() + " / "
                + u.cacheReadTokens() + " / " + u.cacheWriteTokens() + " |\n"
                + "| Input tokens read from the cache | " + cachedShare(u) + " |\n"
                + "| Output tokens | " + u.outputTokens() + " |\n"
                + "| Calls without usage | " + u.callsWithoutUsage() + " |\n"
                + "| Estimated cost, turns (USD) | " + money(u.cost()) + " |\n"
                + "| Estimated cost, usage ledger (USD, turns and summaries) | " + money(run.ledgerCost()) + " |\n"
                + "| Turn latency mean / median / max (s) | " + seconds(l.meanTurnMillis()) + " / "
                + seconds(l.medianTurnMillis()) + " / " + seconds(l.maxTurnMillis()) + " |\n"
                + "| Question latency mean / max (s) | " + seconds(l.meanQuestionMillis()) + " / "
                + seconds(l.maxQuestionMillis()) + " |\n\n"
                + "Search embeddings (a few Titan tokens per search) and the index build are outside these costs (§8). "
                + "Uncached input tokens are the provider's own count outside the cached prefix (tools, system prompt, "
                + "context block); cache reads and writes are priced apart, as the price file lists them.\n"
                + (cachingSilent(u) ? "\n**Caching was asked for, but the model reported no cached tokens**: it may not "
                + "cache through this provider, or the prefix is below its minimum (Haiku 4.5: 4,096 tokens). The run "
                + "was priced as uncached.\n" : "");
    }

    /** The caching line of the run's identity: on, or off and why (step 8c). */
    String caching() {
        String status = run.promptCaching();
        if (status == null) {
            return "not recorded";
        }
        return switch (status) {
            case "ON" -> cachingSilent(metrics.usage()) ? "on (tools, system prompt, context block; 5-minute TTL), "
                    + "but no cached tokens were reported" : "on (tools, system prompt, context block; 5-minute TTL)";
            case "OFF_SETTING" -> "off (finalysis.ai.chat.prompt-cache is false); every call uncached";
            case "OFF_NO_CACHE_PRICES" -> "off (the price file lists no cache prices for this model); every call "
                    + "uncached";
            case "OFF_PROVIDER" -> "off (the provider has no cache points); every call uncached";
            case "OFF_REFUSED" -> "off (the provider refused cache points for this model; those calls were sent "
                    + "again uncached)";
            default -> status;
        };
    }

    private boolean cachingSilent(ChatEvalMetrics.Usage u) {
        return "ON".equals(run.promptCaching()) && u.cacheReadTokens() + u.cacheWriteTokens() == 0
                && u.inputTokens() > 0;
    }

    private static String cachedShare(ChatEvalMetrics.Usage u) {
        return u.allInputTokens() == 0 ? "–" : BigDecimal.valueOf(u.cacheReadTokens() * 100)
                .divide(BigDecimal.valueOf(u.allInputTokens()), 1, java.math.RoundingMode.HALF_EVEN) + "%";
    }

    private static String questionTable(List<QuestionResult> questions) {
        StringBuilder table = new StringBuilder("| Question | Category | Correct | Turns | Tools ok | Sources "
                + "| Retried | Limit | Tokens in (cached) / out | Cost (USD) | Time (s) |\n"
                + "|---|---|---|---:|---:|---|---:|---|---:|---:|---:|\n");
        for (QuestionResult q : questions) {
            List<Observation> o = q.turns().stream().map(TurnResult::observed).toList();
            table.append("| ").append(q.id()).append(" | ").append(q.category()).append(" | ")
                    .append(q.correct() ? "yes" : "**no**").append(" | ")
                    .append(q.turns().stream().filter(t -> t.score().correct()).count()).append(" / ")
                    .append(q.expectedTurns()).append(" | ")
                    .append(q.turns().stream().filter(t -> t.score().toolChoice()).count()).append(" | ")
                    .append(o.stream().map(x -> String.valueOf(x.source())).collect(Collectors.joining(", ")))
                    .append(" | ").append(o.stream().filter(Observation::retried).count()).append(" | ")
                    .append(o.stream().map(Observation::limit).filter(l -> l != null).map(String::valueOf)
                            .collect(Collectors.joining(", "))).append(" | ")
                    .append(o.stream().mapToLong(x -> x.inputTokens() == null ? 0 : x.inputTokens()).sum())
                    .append(" (").append(o.stream().mapToLong(x -> x.cacheReadTokens() + x.cacheWriteTokens()).sum())
                    .append(") / ")
                    .append(o.stream().mapToLong(x -> x.outputTokens() == null ? 0 : x.outputTokens()).sum())
                    .append(" | ").append(money(o.stream().map(x -> x.cost() == null ? BigDecimal.ZERO : x.cost())
                            .reduce(BigDecimal.ZERO, BigDecimal::add)))
                    .append(" | ").append(seconds(q.millis())).append(" |\n");
        }
        return table.toString();
    }

    private String wrongTurns() {
        StringBuilder md = new StringBuilder();
        for (QuestionResult q : run.questions()) {
            for (TurnResult t : q.turns()) {
                if (!t.score().correct()) {
                    md.append("- **").append(q.id()).append(" turn ").append(t.turn()).append("**: ")
                            .append(t.score().reason()).append("; tools ").append(tools(t.observed())).append("\n");
                }
            }
            if (q.turns().size() < q.expectedTurns()) {
                md.append("- **").append(q.id()).append("**: turns after ").append(q.turns().size())
                        .append(" not run (an earlier turn failed)\n");
            }
        }
        return md.isEmpty() ? "None.\n" : md.toString();
    }

    private String answers(QuestionResult q) {
        StringBuilder md = new StringBuilder("### ").append(q.id()).append(" (").append(q.category())
                .append(q.userWritten() ? ", yours" : "").append(")\n\n");
        ExpectedTurns expectedTurns = expected.get(q.id());
        for (TurnResult t : q.turns()) {
            Observation o = t.observed();
            md.append("**Turn ").append(t.turn()).append(":** ").append(t.ask()).append("\n\n");
            md.append("- Expected: ").append(expectedTurns == null ? NA : expectedTurns.turns().get(t.turn() - 1))
                    .append(t.note() == null ? "" : " (" + t.note() + ")").append("\n");
            md.append("- Result: ").append(t.score().correct() ? "correct" : "**wrong**").append(", ")
                    .append(t.score().reason()).append("\n");
            md.append("- Source ").append(o.source()).append(o.grounding() == null ? "" : ", " + o.grounding())
                    .append(o.retried() ? ", retried" + (o.retryFigures().isEmpty() ? " (cut answer)"
                            : " for " + o.retryFigures()) : "")
                    .append(o.limit() == null ? "" : ", limit " + o.limit())
                    .append("; tools ").append(tools(o))
                    .append(o.searchQueries().isEmpty() ? "" : "; searched " + o.searchQueries())
                    .append(o.invalidArguments().isEmpty() ? "" : "; invalid " + o.invalidArguments().stream()
                            .map(i -> i.tool() + "." + i.parameter() + " " + i.problem())
                            .collect(Collectors.joining(", ")))
                    .append(o.citationsFound() == null ? "" : "; expected citations "
                            + (o.citationsFound() ? "present" : "**missing**")).append("\n\n");
            md.append(quote(o.answer() == null ? "(no answer: " + o.problem() + ")" : o.answer()));
            if (o.rejectedAnswer() != null) {
                md.append("First answer (not shown):\n\n").append(quote(o.rejectedAnswer()));
            }
        }
        return md.toString();
    }

    /**
     * Every problem of the run's invalid tool calls, counted by tool, parameter and problem (step 8b), most frequent
     * first.
     */
    String invalidTable() {
        Map<String, Long> counts = run.questions().stream().flatMap(q -> q.turns().stream())
                .flatMap(t -> t.observed().invalidArguments().stream())
                .collect(Collectors.groupingBy(i -> "| " + i.tool() + " | " + i.parameter() + " | " + i.problem()
                        + " | ", java.util.TreeMap::new, Collectors.counting()));
        if (counts.isEmpty()) {
            return "No tool call had invalid arguments.\n";
        }
        StringBuilder md = new StringBuilder("| Tool | Parameter | Problem | Count |\n|---|---|---|---:|\n");
        counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> md.append(e.getKey()).append(e.getValue()).append(" |\n"));
        return md.toString();
    }

    private static String tools(Observation o) {
        return o.tools().isEmpty() ? "none" : o.tools().stream()
                .map(t -> (t.name() == null ? "(unknown)" : t.name()) + (t.status().equals("OK") ? "" : " " + t.status()))
                .collect(Collectors.joining(", "));
    }

    static String quote(String text) {
        return "> " + text.strip().replace("\r", "").replace("\n", "\n> ") + "\n\n";
    }

    private static String pct(BigDecimal value) {
        return value == null ? NA : value.toPlainString() + "%";
    }

    private static String money(BigDecimal value) {
        return value == null ? NA : value.setScale(4, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static String seconds(long millis) {
        return BigDecimal.valueOf(millis).divide(BigDecimal.valueOf(1000), 1, RoundingMode.HALF_UP).toPlainString();
    }
}

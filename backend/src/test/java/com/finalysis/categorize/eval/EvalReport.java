package com.finalysis.categorize.eval;

import com.finalysis.categorize.eval.EvalMetrics.Band;
import com.finalysis.categorize.eval.EvalMetrics.Counts;
import com.finalysis.categorize.eval.EvalMetrics.RowSource;
import com.finalysis.categorize.eval.EvalMetrics.ScoredRow;
import com.finalysis.categorize.eval.EvalMetrics.SweepPoint;
import com.finalysis.categorize.eval.EvalRunner.EvalResult;
import com.finalysis.categorize.eval.EvalRunner.ExamplesStats;
import com.finalysis.categorize.eval.EvalRunner.Usage;
import com.finalysis.categorize.eval.EvalRunner.Variant;
import com.finalysis.categorize.eval.EvalRunner.VariantResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * One evaluation's results with what identifies the run (docs/plans/phase-4.md, step 9a notes), written to
 * {@code docs/eval/} as {@code <set>-<commit>-<timestamp>.json} (everything, row by row) and {@code .md} (the tables).
 *
 * @param commit    the full commit hash, or {@code unknown}
 * @param dirty     the working tree had changes (holdout runs refuse that)
 * @param startedAt ISO-8601, UTC
 */
record EvalReport(String commit, boolean dirty, String startedAt, EvalResult result) {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final String NA = "n/a";

    static EvalReport of(EvalGuards.GitState git, Instant startedAt, EvalResult result) {
        return new EvalReport(git.commit(), git.dirty(), startedAt.toString(), result);
    }

    /** {@code <set>-<first 12 of the commit>-<yyyyMMddTHHmmssZ>}: what both files are named. */
    String fileStem() {
        String commit12 = commit.length() > 12 ? commit.substring(0, 12) : commit;
        return result.set() + "-" + commit12 + "-" + STAMP.format(Instant.parse(startedAt));
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

    /** What the console shows: the summary table and, for a dev run, the sweep. */
    String consoleSummary() {
        return "Categorization eval: " + result.set() + " (" + result.rows() + " rows, commit " + commit
                + (dirty ? ", dirty" : "") + ")\n\n" + summaryTable() + "\n" + sweepTables();
    }

    String markdown() {
        StringBuilder md = new StringBuilder();
        md.append("# Categorization eval: ").append(result.set()).append("\n\n");
        md.append("- Set: `").append(result.set()).append("`, ").append(result.rows()).append(" rows, SHA-256 `")
                .append(result.setSha256()).append("`\n");
        md.append("- Commit: `").append(commit).append("`").append(dirty ? " (working tree dirty)" : " (clean)")
                .append("\n");
        md.append("- Started: ").append(startedAt).append("\n");
        md.append("- Inputs: min-confidence ").append(result.inputs().minConfidence().toPlainString())
                .append(", direct-min-similarity ").append(result.inputs().directMinSimilarity()).append("\n");
        md.append("- Seeding (import, cleaning, seed rules): ").append(seconds(result.seedMillis())).append(" s\n");
        md.append("- Prices: ").append(result.pricesSource()).append("\n\n");
        md.append("Definitions: coverage = categorized / rows; accuracy = correct / categorized; overall = correct / "
                + "rows; review = left for the review queue (low confidence or invalid answer). Costs are estimates; "
                + "`>=` marks a lower bound (a call reported no usage).\n\n");
        md.append("## Summary\n\n").append(summaryTable()).append("\n");
        md.append("## By source\n\n").append(bySourceTable()).append("\n");
        md.append("## Examples\n\n").append(examplesTable()).append("\n");
        md.append("Measured examples share of input tokens ((C - B) / C): ")
                .append(pct(result.measuredExamplesSharePct()))
                .append(". Needs both variants to report usage for every call and to make the same number of calls."
                        + "\n\n");
        md.append("## Confidence\n\n").append(confidenceTable()).append("\n");
        String sweep = sweepTables();
        if (!sweep.isEmpty()) {
            md.append("## Threshold sweep\n\nFrom this run's saved confidences; no further calls.\n\n").append(sweep);
        }
        md.append("## Settings\n\n");
        result.settings().forEach((key, value) -> md.append("- `").append(key).append("`: `").append(value)
                .append("`\n"));
        md.append("\n");
        for (VariantResult variant : result.variants()) {
            if (!variant.settings().isEmpty()) {
                md.append("- ").append(variant.variant()).append(": ").append(variant.settings().entrySet().stream()
                        .map(entry -> entry.getKey() + " " + entry.getValue()).collect(Collectors.joining(", ")))
                        .append("\n");
            }
        }
        if (result.inputs().sweep()) {
            md.append("\n## Wrong rows\n\n").append(wrongRows());
        }
        return md.toString();
    }

    private String summaryTable() {
        StringBuilder table = new StringBuilder("| Variant | Status | Coverage | Accuracy (categorized) | Wrong "
                + "| Review | Overall correct | Chat calls | Retries | Embedding calls | Calls without usage "
                + "| Input tokens | Output tokens | Embedding tokens | Est. cost (USD) | Wall time (s) |\n");
        table.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (VariantResult variant : result.variants()) {
            Usage usage = variant.usage();
            table.append("| ").append(variant.variant()).append(": ").append(variant.description())
                    .append(" | ").append(variant.status())
                    .append(" | ").append(pct(variant.metrics().coveragePct()))
                    .append(" | ").append(pct(variant.metrics().accuracyPct()))
                    .append(" | ").append(variant.metrics().wrong())
                    .append(" | ").append(variant.metrics().review())
                    .append(" | ").append(pct(variant.metrics().overallPct()))
                    .append(" | ").append(usage.chatCalls())
                    .append(" | ").append(usage.retries())
                    .append(" | ").append(usage.embeddingCalls())
                    .append(" | ").append(usage.chatCallsWithoutUsage() + usage.embeddingCallsWithoutUsage())
                    .append(" | ").append(usage.inputTokens())
                    .append(" | ").append(usage.outputTokens())
                    .append(" | ").append(usage.embeddingInputTokens())
                    .append(" | ").append(cost(usage))
                    .append(" | ").append(seconds(variant.wallMillis())).append(" |\n");
        }
        return table.toString();
    }

    private String bySourceTable() {
        StringBuilder table = new StringBuilder("| Variant | Rule (correct / categorized) | AI | Direct "
                + "| Uncategorized |\n|---|---:|---:|---:|---:|\n");
        for (VariantResult variant : result.variants()) {
            Map<RowSource, Counts> sources = variant.metrics().bySource();
            table.append("| ").append(variant.variant())
                    .append(" | ").append(counts(sources.get(RowSource.RULE)))
                    .append(" | ").append(counts(sources.get(RowSource.AI)))
                    .append(" | ").append(counts(sources.get(RowSource.DIRECT)))
                    .append(" | ").append(variant.metrics().uncategorized()).append(" |\n");
        }
        return table.toString();
    }

    private String examplesTable() {
        StringBuilder table = new StringBuilder("| Variant | Items sent | Items with examples (before the cap) | Direct "
                + "| Batches | Examples per batch (min / mean / max) | Cap | Est. share of input (by characters) |\n"
                + "|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (VariantResult variant : result.variants()) {
            if (variant.variant() == Variant.A) {
                continue;
            }
            ExamplesStats examples = variant.examples();
            table.append("| ").append(variant.variant())
                    .append(" | ").append(variant.usage().itemsSent())
                    .append(" | ").append(variant.usage().itemsWithExamples())
                    .append(" | ").append(variant.usage().itemsDirect())
                    .append(" | ").append(examples.batches())
                    .append(" | ").append(examples.batches() == 0 ? NA
                            : examples.min() + " / " + examples.mean().toPlainString() + " / " + examples.max())
                    .append(" | ").append(examples.cap())
                    .append(" | ").append(pct(examples.estimatedSharePct())).append(" |\n");
        }
        return table.toString();
    }

    private String confidenceTable() {
        List<VariantResult> ai = result.variants().stream().filter(variant -> variant.variant() != Variant.A).toList();
        StringBuilder table = new StringBuilder("Answered rows (applied or not) by the model's confidence, and how "
                + "many of them the model got right.\n\n| Band |");
        ai.forEach(variant -> table.append(" ").append(variant.variant()).append(" rows | ").append(variant.variant())
                .append(" accuracy |"));
        table.append("\n|---|").append("---:|---:|".repeat(ai.size())).append("\n");
        for (int band = 0; band < EvalMetrics.BANDS; band++) {
            table.append("| ").append(ai.getFirst().metrics().confidenceBands().get(band).range()).append(" |");
            for (VariantResult variant : ai) {
                Band b = variant.metrics().confidenceBands().get(band);
                table.append(" ").append(b.rows()).append(" | ").append(pct(b.accuracyPct())).append(" |");
            }
            table.append("\n");
        }
        return table.toString();
    }

    private String sweepTables() {
        StringBuilder tables = new StringBuilder();
        for (VariantResult variant : result.variants()) {
            if (variant.sweep() == null) {
                continue;
            }
            tables.append("Sweep, ").append(variant.variant()).append(": ").append(variant.description()).append("\n\n")
                    .append("| min-confidence | Coverage | Accuracy (categorized) | Wrong | Review "
                            + "| Overall correct |\n")
                    .append("|---:|---:|---:|---:|---:|---:|\n");
            for (SweepPoint point : variant.sweep()) {
                tables.append("| ").append(point.minConfidence().toPlainString())
                        .append(" | ").append(pct(point.coveragePct()))
                        .append(" | ").append(pct(point.accuracyPct()))
                        .append(" | ").append(point.wrong())
                        .append(" | ").append(point.review())
                        .append(" | ").append(pct(point.overallPct())).append(" |\n");
            }
            tables.append("\n");
        }
        return tables.toString();
    }

    private String wrongRows() {
        StringBuilder rows = new StringBuilder();
        for (VariantResult variant : result.variants()) {
            List<ScoredRow> wrong = variant.rows().stream().filter(row -> row.categorized() && !row.correct()).toList();
            rows.append(variant.variant()).append(": ").append(wrong.size()).append(" wrong\n\n");
            if (wrong.isEmpty()) {
                continue;
            }
            rows.append("| Row | Description | Expected | Got | Source | Confidence |\n|---:|---|---|---|---|---:|\n");
            for (ScoredRow row : wrong) {
                rows.append("| ").append(row.number()).append(" | `").append(row.description()).append("` | ")
                        .append(row.expected()).append(" | ").append(row.predicted()).append(" | ")
                        .append(row.source()).append(" | ")
                        .append(row.confidence() == null ? NA : row.confidence().toPlainString()).append(" |\n");
            }
            rows.append("\n");
        }
        return rows.toString();
    }

    private static String counts(Counts counts) {
        return counts == null ? "0 / 0" : counts.correct() + " / " + counts.categorized();
    }

    private static String cost(Usage usage) {
        if (usage.estimatedCostUsd() == null) {
            return NA + " (unpriced model)";
        }
        return (usage.costIsLowerBound() ? ">= " : "") + usage.estimatedCostUsd().setScale(4, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String pct(BigDecimal value) {
        return value == null ? NA : value.toPlainString() + "%";
    }

    private static String seconds(long millis) {
        return BigDecimal.valueOf(millis).divide(BigDecimal.valueOf(1000), 1, RoundingMode.HALF_UP).toPlainString();
    }
}

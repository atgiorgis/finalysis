package com.finalysis.chat.eval;

import com.finalysis.chat.eval.SearchSweep.AtFloor;
import com.finalysis.chat.eval.SearchSweep.Candidate;
import com.finalysis.chat.eval.SearchSweep.FloorSummary;
import com.finalysis.chat.eval.SearchSweep.QueryResult;
import com.finalysis.chat.eval.SearchSweepRunner.Sweep;
import java.io.IOException;
import java.math.BigDecimal;
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
 * The search-floor sweep's results (step 8), in {@code docs/eval/} as
 * {@code chat-sweep-dev-<commit12>-<timestamp>.json} and {@code .md}.
 *
 * @param settings the search and embedding settings of the run, by property name
 */
record SearchSweepReport(String commit, boolean dirty, String startedAt, String embeddingModel,
                         Map<String, String> settings, Sweep sweep) {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    String fileStem() {
        String commit12 = commit.length() > 12 ? commit.substring(0, 12) : commit;
        return "chat-sweep-" + sweep.set() + "-" + commit12 + "-" + STAMP.format(Instant.parse(startedAt));
    }

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

    String markdown() {
        StringBuilder md = new StringBuilder("# Chat search floor sweep: ").append(sweep.set()).append("\n\n");
        md.append("- Set: `").append(sweep.set()).append("`, SHA-256 `").append(sweep.setSha256()).append("`\n");
        md.append("- Commit: `").append(commit).append("`").append(dirty ? " (working tree dirty)" : " (clean)")
                .append("; started ").append(startedAt).append("\n");
        md.append("- Embedding model: `").append(embeddingModel).append("`; no chat model was called\n");
        md.append("- Queries: `").append(SearchSweepRunner.QUERIES).append("`, each run once through "
                + "`search_transactions` (limit 10) at the lowest floor; higher floors are worked out from it "
                + "(similarities are the tool's two-decimal values)\n\n");
        md.append(summaryTable()).append("\n");
        BigDecimal lossless = sweep.highestLossless();
        md.append("Highest floor that keeps every target found at ").append(SearchSweep.FLOORS.getFirst())
                .append(": ").append(lossless == null ? "n/a (no query finds its targets)" : lossless.toPlainString())
                .append(". Choosing the floor is yours: a lower one finds more, a higher one shows the model fewer "
                        + "unrelated candidates.\n\n");
        md.append("## Per query\n\n");
        for (QueryResult q : sweep.queries()) {
            md.append("### ").append(q.question()).append(": \"").append(q.query()).append("\"\n\n");
            md.append("Targets ").append(q.targets()).append("; status ").append(q.status()).append("\n\n");
            md.append("| Candidate | Match | Similarity | Target |\n|---|---|---:|---|\n");
            for (Candidate c : q.candidates()) {
                boolean target = q.targets().stream().anyMatch(t -> c.merchant().toUpperCase().contains(t));
                md.append("| ").append(c.merchant()).append(" | ").append(c.match()).append(" | ")
                        .append(c.similarity() == null ? "" : c.similarity().toPlainString()).append(" | ")
                        .append(target ? "yes" : "").append(" |\n");
            }
            md.append("\n| Floor | Candidates | Targets found | Others |\n|---:|---:|---|---:|\n");
            for (AtFloor at : q.floors()) {
                md.append("| ").append(at.floor().toPlainString()).append(" | ").append(at.candidates().size())
                        .append(" | ").append(at.found().size()).append(" / ").append(q.targets().size())
                        .append(" | ").append(at.others()).append(" |\n");
            }
            md.append("\n");
        }
        md.append("## Search index\n\n").append(sweep.index().stream().map(i -> "- " + i.dataset() + ": " + i.status()
                + ", " + i.merchants() + " merchants, " + i.embedded() + " embedded, " + i.remaining() + " left over")
                .collect(Collectors.joining("\n"))).append("\n\n## Settings\n\n");
        settings.forEach((k, v) -> md.append("- `").append(k).append("`: `").append(v).append("`\n"));
        return md.toString();
    }

    String summaryTable() {
        StringBuilder table = new StringBuilder("| Floor | Queries with every target | Questions with every target "
                + "(any query) | Targets found | Candidates | Non-target candidates |\n"
                + "|---:|---:|---:|---:|---:|---:|\n");
        for (FloorSummary f : sweep.floors()) {
            table.append("| ").append(f.floor().toPlainString()).append(" | ").append(f.queriesAllFound())
                    .append(" / ").append(f.queries()).append(" | ").append(f.questionsAllFound()).append(" / ")
                    .append(f.questions()).append(" | ").append(f.targetsFound()).append(" / ").append(f.targets())
                    .append(" | ").append(f.candidates()).append(" | ").append(f.others()).append(" |\n");
        }
        return table.toString();
    }
}

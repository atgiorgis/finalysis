package com.finalysis.categorize.eval;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * A labeled categorization set in the dev-set format ({@code description,amount,expected_category,name}; comment
 * lines first), as the accuracy harness reads it (docs/plans/phase-4.md §10, step 9a notes).
 *
 * <p>The labels never reach the database: {@link #statementLines()}, what {@link EvalSeeder} imports, has no label,
 * and {@link #expected(int)} is read only when scoring.
 *
 * @param sha256 the file's SHA-256, recorded with the results
 */
public record EvalSet(String name, String sha256, List<LabeledRow> rows) {

    static final String HEADER = "description,amount,expected_category,name";
    /** Row n is dated {@code FIRST_DATE + (n - 1)} days, so every row has its own date and is found by it. */
    static final LocalDate FIRST_DATE = LocalDate.of(2025, 1, 1);

    public EvalSet {
        rows = List.copyOf(rows);
    }

    /** One row and its answer; {@code number} counts from 1 in file order. */
    public record LabeledRow(int number, String description, BigDecimal amount, String expected) {
    }

    /** What is imported for a row: a date, the description exactly as written, the amount. No label. */
    public record StatementLine(int number, LocalDate date, String description, BigDecimal amount) {
    }

    /** Reads a set; its cells never contain a comma or a quote (the generator guarantees it). */
    public static EvalSet load(String name, Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        List<String> lines = Files.readAllLines(file).stream().filter(line -> !line.startsWith("#")).toList();
        if (lines.isEmpty() || !lines.getFirst().equals(HEADER)) {
            throw new IllegalArgumentException(file + " does not start with the header " + HEADER);
        }
        List<LabeledRow> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",", -1);
            if (cells.length != 4) {
                throw new IllegalArgumentException(file + " row " + (rows.size() + 1) + " does not have 4 cells");
            }
            rows.add(new LabeledRow(rows.size() + 1, cells[0], new BigDecimal(cells[1]), cells[2]));
        }
        return new EvalSet(name, sha256(bytes), rows);
    }

    public List<StatementLine> statementLines() {
        return rows.stream()
                .map(row -> new StatementLine(row.number(), FIRST_DATE.plusDays(row.number() - 1L), row.description(),
                        row.amount()))
                .toList();
    }

    /** The answer for row {@code number}: for scoring only. */
    public String expected(int number) {
        return rows.get(number - 1).expected();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}

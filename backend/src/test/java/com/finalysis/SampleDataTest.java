package com.finalysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Checks the committed synthetic data in {@code sample-data/} (produced by
 * {@code tools/SampleDataGenerator.java}) is internally consistent, so later phases can trust it.
 * The per-format readers here are deliberately minimal; real parsing belongs to {@code StatementParser}s.
 */
class SampleDataTest {

    private static final Path SAMPLE_DATA = Path.of("..", "sample-data");
    private static final DateTimeFormatter NORTHFIELD_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final DateTimeFormatter SUMMIT_DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
    private static final Map<String, String> CHECKING_PAYMENT_DESCRIPTIONS = Map.of(
            "apex-visa", "APEX VISA AUTOPAY",
            "summit-card", "SUMMIT CARD ONLINE PMT");

    record ManifestEntry(String file, String account, LocalDate periodStart, LocalDate periodEnd,
                         BigDecimal openingBalance, BigDecimal closingBalance) {
        boolean broken() {
            return file.startsWith("broken/");
        }
    }

    /** A statement row in the app's sign convention (negative = money out); balance is null if the format has none. */
    record Row(LocalDate date, String description, BigDecimal amount, BigDecimal balance) {}

    @Test
    void generatedFilesExist() {
        List<ManifestEntry> manifest = manifest();

        assertThat(manifest).hasSize(26);
        assertThat(manifest).filteredOn(ManifestEntry::broken).hasSize(1);
        assertThat(SAMPLE_DATA.resolve("answer-key.csv")).isRegularFile();
        for (ManifestEntry entry : manifest) {
            assertThat(SAMPLE_DATA.resolve(entry.file())).isRegularFile();
        }
    }

    @Test
    void everyStatementReconcilesWithManifest() {
        for (ManifestEntry entry : statements()) {
            assertThat(reconciles(entry)).as("%s reconciles", entry.file()).isTrue();

            BigDecimal balance = entry.openingBalance();
            for (Row row : rows(entry)) {
                balance = balance.add(row.amount());
                if (row.balance() != null) {
                    assertThat(row.balance()).as("running balance in %s on %s", entry.file(), row.date())
                            .isEqualByComparingTo(balance);
                }
            }
        }
    }

    @Test
    void brokenStatementDoesNotReconcile() {
        List<ManifestEntry> broken = manifest().stream().filter(ManifestEntry::broken).toList();

        assertThat(broken).isNotEmpty();
        for (ManifestEntry entry : broken) {
            assertThat(reconciles(entry)).as("%s reconciles", entry.file()).isFalse();
        }
    }

    @Test
    void everyCardPaymentHasMatchingCheckingTransfer() {
        List<Row> checking = rowsFor("northfield-checking");

        CHECKING_PAYMENT_DESCRIPTIONS.forEach((card, checkingDescription) -> {
            List<Row> payments = rowsFor(card).stream().filter(r -> r.amount().signum() > 0).toList();
            assertThat(payments).as("%s payments", card).isNotEmpty();

            List<Row> matched = new ArrayList<>();
            for (Row payment : payments) {
                List<Row> candidates = checking.stream()
                        .filter(c -> c.amount().compareTo(payment.amount().negate()) == 0)
                        .filter(c -> {
                            long days = ChronoUnit.DAYS.between(c.date(), payment.date());
                            return days >= 1 && days <= 3;
                        })
                        .toList();
                assertThat(candidates).as("checking side of %s payment on %s", card, payment.date()).hasSize(1);
                assertThat(candidates.get(0).description()).isNotEqualTo(payment.description());
                matched.add(candidates.get(0));
            }

            List<Row> checkingPayments = checking.stream()
                    .filter(c -> c.description().equals(checkingDescription)).toList();
            assertThat(matched).as("every %s payment in checking reaches the card", card)
                    .containsExactlyInAnyOrderElementsOf(checkingPayments);
        });
    }

    @Test
    void answerKeyMatchesStatements() {
        Map<String, List<Row>> answerKey = new LinkedHashMap<>();
        for (String[] c : readCsv(SAMPLE_DATA.resolve("answer-key.csv"))) {
            answerKey.computeIfAbsent(c[0], file -> new ArrayList<>())
                    .add(new Row(LocalDate.parse(c[2]), c[3], new BigDecimal(c[4]), null));
        }

        List<ManifestEntry> statements = statements();
        assertThat(answerKey.keySet()).containsExactlyElementsOf(statements.stream().map(ManifestEntry::file).toList());
        for (ManifestEntry entry : statements) {
            List<Row> fromStatement = rows(entry).stream()
                    .map(r -> new Row(r.date(), r.description(), r.amount(), null)).toList();
            assertThat(answerKey.get(entry.file())).as(entry.file()).containsExactlyElementsOf(fromStatement);
        }
    }

    private static boolean reconciles(ManifestEntry entry) {
        BigDecimal closing = rows(entry).stream().map(Row::amount).reduce(entry.openingBalance(), BigDecimal::add);
        return closing.compareTo(entry.closingBalance()) == 0;
    }

    private static List<ManifestEntry> manifest() {
        return readCsv(SAMPLE_DATA.resolve("statements-manifest.csv")).stream()
                .map(c -> new ManifestEntry(c[0], c[1], LocalDate.parse(c[2]), LocalDate.parse(c[3]),
                        new BigDecimal(c[4]), new BigDecimal(c[5])))
                .toList();
    }

    private static List<ManifestEntry> statements() {
        return manifest().stream().filter(e -> !e.broken()).toList();
    }

    private static List<Row> rowsFor(String account) {
        return statements().stream().filter(e -> e.account().equals(account))
                .flatMap(e -> rows(e).stream()).toList();
    }

    private static List<Row> rows(ManifestEntry entry) {
        return readCsv(SAMPLE_DATA.resolve(entry.file())).stream()
                .map(c -> switch (entry.account()) {
                    // Northfield: separate positive Debit/Credit columns plus a running balance.
                    case "northfield-checking", "northfield-savings" -> new Row(
                            LocalDate.parse(c[0], NORTHFIELD_DATE), c[1],
                            amount(c[3]).subtract(amount(c[2])), new BigDecimal(c[4]));
                    // Apex: purchases positive, payments negative.
                    case "apex-visa" -> new Row(LocalDate.parse(c[0]), c[2], new BigDecimal(c[4]).negate(), null);
                    // Summit: purchases negative, payments positive.
                    case "summit-card" -> new Row(LocalDate.parse(c[0], SUMMIT_DATE), c[1], new BigDecimal(c[2]), null);
                    default -> throw new IllegalArgumentException("unknown account " + entry.account());
                })
                .toList();
    }

    private static BigDecimal amount(String value) {
        return value.isEmpty() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    /** Reads a header-plus-rows CSV; the generator guarantees no field contains a comma or quote. */
    private static List<String[]> readCsv(Path path) {
        try {
            return Files.readAllLines(path).stream().skip(1).map(line -> line.split(",", -1)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

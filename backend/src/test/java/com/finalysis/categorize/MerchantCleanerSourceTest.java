package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps merchant cleaning bank-agnostic (docs/plans/phase-4.md §2.2): no string literal in the
 * {@code categorize} main sources contains a word that the cleaning rules file uses in a pattern
 * (processor, ACH and payment-service names such as {@code CHECKCARD}, {@code INDN} or
 * {@code ZELLE}). That knowledge belongs in {@code merchant-cleaning.yml}, so adding a bank's format
 * never means changing Java.
 *
 * <p>Words are upper-case runs of three or more letters in the file's patterns. SQL literals (text
 * starting with SELECT, INSERT, UPDATE or DELETE) are skipped: SQL keywords such as FROM are not
 * format knowledge, and the queries never filter on description text.
 */
class MerchantCleanerSourceTest {

    private static final Path SOURCES = Path.of("src/main/java/com/finalysis/categorize");
    private static final Path RULES = Path.of("src/main/resources", MerchantCleaningRules.RESOURCE);
    private static final Pattern WORD = Pattern.compile("[A-Z]{3,}");
    // Text blocks first, so their content isn't read as a run of ordinary literals.
    private static final Pattern LITERAL = Pattern.compile("\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\"", Pattern.DOTALL);
    private static final Pattern SQL = Pattern.compile("^\\s*(SELECT|INSERT|UPDATE|DELETE)\\b");

    @Test
    void noCategorizeSourceHoldsAWordFromTheCleaningPatterns() throws IOException {
        Set<String> vocabulary = vocabulary(Files.readString(RULES));
        assertThat(vocabulary).as("the rules file must yield words, or this test checks nothing")
                .contains("CHECKCARD", "INDN", "ZELLE", "VENMO", "AMZN");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                for (String word : violations(Files.readString(file), vocabulary)) {
                    violations.add(SOURCES.relativize(file) + ": " + word);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    /** The check catches a violation, so a passing run means something. */
    @Test
    void theCheckFindsAWordInALiteral() {
        Set<String> vocabulary = Set.of("CHECKCARD", "FROM");
        String source = """
                class X {
                    static final String P = "^CHECKCARD \\\\d{4}"; // a bank's prefix hard-coded
                    static final String Q = "SELECT id FROM txn";  // SQL: skipped
                    // CHECKCARD in a comment is fine
                }
                """;

        assertThat(violations(source, vocabulary)).containsExactly("CHECKCARD");
    }

    static Set<String> vocabulary(String yaml) {
        Set<String> words = new TreeSet<>();
        for (String line : yaml.lines().toList()) {
            String content = line.strip();
            int comment = content.indexOf(" #");
            if (content.startsWith("#") || !content.startsWith("- '")) {
                continue; // only pattern entries; comments and the state list are not format words
            }
            String pattern = comment > 0 ? content.substring(0, comment) : content;
            Matcher m = WORD.matcher(pattern);
            while (m.find()) {
                words.add(m.group());
            }
        }
        return words;
    }

    static List<String> violations(String source, Set<String> vocabulary) {
        List<String> found = new ArrayList<>();
        Matcher literal = LITERAL.matcher(withoutComments(source));
        while (literal.find()) {
            String text = literal.group(1) != null ? literal.group(1) : literal.group(2);
            if (SQL.matcher(text).find()) {
                continue;
            }
            Matcher word = WORD.matcher(text);
            while (word.find()) {
                if (vocabulary.contains(word.group())) {
                    found.add(word.group());
                }
            }
        }
        return found;
    }

    /** Line and block comments removed, so Javadoc examples (which name real formats) don't count. */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}

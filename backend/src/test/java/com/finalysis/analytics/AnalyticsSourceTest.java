package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * GR-01 and GR-02 in the analytics and alert sources (docs/plans/phase-5.md §4): SQL sums and counts exactly, Java
 * divides with {@code BigDecimal}, and nothing is ever binary floating point.
 * <ul>
 *   <li>No string literal (SQL lives in them) uses {@code avg(}, {@code round(}, {@code percentile_cont} (it returns
 *       {@code double precision}), {@code float} or {@code double precision}. Averages and rounding are Java's, so
 *       there is one rounding mode, {@code HALF_EVEN}.</li>
 *   <li>No code outside comments and literals names {@code double}, {@code float} or their boxes.</li>
 * </ul>
 */
class AnalyticsSourceTest {

    private static final List<Path> SOURCES = List.of(
            Path.of("src/main/java/com/finalysis/analytics"), Path.of("src/main/java/com/finalysis/alert"));
    // Text blocks first, so their content isn't read as a run of ordinary literals.
    private static final Pattern LITERAL = Pattern.compile("\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\"", Pattern.DOTALL);
    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);
    private static final Pattern FORBIDDEN_SQL =
            Pattern.compile("\\bavg\\s*\\(|\\bround\\s*\\(|\\bpercentile_cont\\b|\\bfloat\\d*\\b|\\bdouble\\s+precision\\b");
    private static final Pattern FORBIDDEN_TYPE = Pattern.compile("\\b(double|float|Double|Float)\\b");

    @Test
    void noAnalyticsSourceUsesFloatingPointOrSqlRounding() throws IOException {
        List<String> violations = new ArrayList<>();
        int files = 0;
        for (Path dir : SOURCES) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path file : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    files++;
                    violations(Files.readString(file)).forEach(v -> violations.add(file.getFileName() + ": " + v));
                }
            }
        }
        assertThat(files).as("the scan must see the sources, or it checks nothing").isGreaterThanOrEqualTo(3);
        assertThat(violations).isEmpty();
    }

    /** The check catches each kind of violation, and leaves comments and allowed SQL alone. */
    @Test
    void theCheckFindsEachViolation() {
        String source = """
                class X {
                    static final String A = "SELECT avg(amount) FROM txn";
                    static final String B = \"""
                            SELECT round(sum(t.amount) / 3, 2), percentile_cont(0.5) WITHIN GROUP (ORDER BY t.amount)
                            FROM txn t\""";
                    static final String C = "SELECT amount::double precision FROM txn";
                    static final String OK = "SELECT sum(amount), percentile_disc(0.5) WITHIN GROUP (ORDER BY amount)";
                    double ratio;                 // a binary float in Java
                    // an average (avg) or a double in a comment is fine
                }
                """;

        assertThat(violations(source)).containsExactly(
                "SQL avg(", "SQL round(", "SQL percentile_cont", "SQL double precision", "type double");
    }

    static List<String> violations(String source) {
        List<String> found = new ArrayList<>();
        String code = COMMENT.matcher(source).replaceAll(" ");
        Matcher literal = LITERAL.matcher(code);
        StringBuilder outside = new StringBuilder();
        int last = 0;
        while (literal.find()) {
            String text = literal.group(1) != null ? literal.group(1) : literal.group(2);
            Matcher sql = FORBIDDEN_SQL.matcher(text.toLowerCase(Locale.ROOT));
            while (sql.find()) {
                found.add("SQL " + sql.group().replaceAll("\\s+", " ").replace(" (", "("));
            }
            outside.append(code, last, literal.start()).append(' ');
            last = literal.end();
        }
        outside.append(code.substring(last));
        Matcher type = FORBIDDEN_TYPE.matcher(outside);
        while (type.find()) {
            found.add("type " + type.group());
        }
        return found;
    }
}

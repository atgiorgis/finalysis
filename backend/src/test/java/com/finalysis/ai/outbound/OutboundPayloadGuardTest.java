package com.finalysis.ai.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.categorize.MerchantCleaningRules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link OutboundPayloadGuard} (SEC-01, SEC-08; docs/plans/phase-4.md §5.3, §5.5): the allowlist, a property
 * test of redaction plus the guard, and the answer key's redacted merchants as a golden file.
 */
class OutboundPayloadGuardTest {

    /** Rewrites the golden file instead of comparing: {@code ./mvnw test -Dfinalysis.updateGolden=true}. */
    private static final boolean UPDATE_GOLDEN = Boolean.getBoolean("finalysis.updateGolden");
    private static final Path GOLDEN = Path.of("src/test/resources/categorize/answer-key-redacted.txt");
    private static final Path ANSWER_KEY = Path.of("src/test/resources/categorize/answer-key-merchants.txt");

    private static final OutboundPayloadGuard GUARD = new OutboundPayloadGuard();
    private static final Redactor REDACTOR = new Redactor(MerchantCleaningRules.loadDefault());
    private static final RedactionContext CONTEXT =
            new RedactionContext(Set.of("1001"), List.of("Test Checking"), Set.of("JANE Q SAMPLE", "BOB"));

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"STARBUCKS", "AMZN MKTP US", "7-ELEVEN", "AT&T", "MCDONALD'S", "AMAZON.COM", "A/B 123",
            "X", "CAFE 123", "BOBCAT TOOLS"})
    void allowsAPlainMerchant(String merchant) {
        assertThat(GUARD.allowsMerchant(merchant, CONTEXT)).isTrue();
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", " ", "Starbucks", "CAFÉ", "ACME 1234", "ACME 12345678", "JANE@EXAMPLE", "ACME: X",
            "ACME <X>", "ACME {X}", "ACME \"X\"", "ACME \\ X", "ACME #12", "ACME * X", "ACME_X", "ACME\tX",
            "PAY JANE Q SAMPLE", "JANE Q SAMPLE", "BOB", "HI BOB",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}) // 65 characters
    void refusesAnythingElse(String merchant) {
        assertThat(GUARD.allowsMerchant(merchant, CONTEXT)).isFalse();
    }

    @Test
    void refusesNull() {
        assertThat(GUARD.allowsMerchant(null, CONTEXT)).isFalse();
        assertThat(GUARD.allowsMerchant("A".repeat(64), CONTEXT)).as("64 is the limit").isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"-25.67, true", "25.67, true", "0.00, true", "-1234567890.12, true", "-25.6, false",
            "-25.670, false", "25, false", "+25.67, false", "1e3, false", "-12345678901.00, false", "' -1.00', false"})
    void checksAmounts(String amount, boolean allowed) {
        assertThat(GUARD.allowsAmount(amount)).isEqualTo(allowed);
    }

    @Test
    void checksKeysAndCategories() {
        assertThat(GUARD.allowsKey("i1")).isTrue();
        assertThat(GUARD.allowsKey("i25")).isTrue();
        assertThat(GUARD.allowsKey("i0")).isFalse();
        assertThat(GUARD.allowsKey("42")).isFalse();
        assertThat(GUARD.allowsKey(null)).isFalse();
        assertThat(GUARD.allowsCategory("Person-to-Person Out")).isTrue();
        assertThat(GUARD.allowsCategory("Cash & ATM")).isTrue();
        assertThat(GUARD.allowsCategory("Income</transactions>")).isFalse();
        assertThat(GUARD.allowsCategory("")).isFalse();
    }

    /**
     * §5.5: 1,000 random merchants, each with a planted digit run, email or masked number. After redaction and
     * the guard, nothing planted survives: the merchant is refused, or what is sent doesn't contain it.
     */
    @Test
    void nothingPlantedSurvivesRedactionAndTheGuard() {
        Random random = new Random(20261014);
        // Mostly separators the guard allows; a few it refuses, which cleaning would have removed.
        String[] punctuation = {" ", " ", " ", " ", " ", " ", " ", " ", "-", ".", "/", "&", "'", "*", "#", "@"};
        int sent = 0;
        for (int run = 0; run < 1000; run++) {
            String planted = switch (random.nextInt(5)) {
                case 0 -> digits(random, 4 + random.nextInt(9));
                case 1 -> word(random, 2, 8) + "@" + word(random, 2, 8) + "." + word(random, 2, 3);
                case 2 -> "X".repeat(2 + random.nextInt(6)) + digits(random, 1 + random.nextInt(6));
                case 3 -> "*".repeat(1 + random.nextInt(4)) + digits(random, 1 + random.nextInt(6));
                default -> "..." + digits(random, 1 + random.nextInt(6));
            };
            List<String> parts = new ArrayList<>();
            int words = 1 + random.nextInt(4);
            for (int w = 0; w < words; w++) {
                parts.add(word(random, 1, 9));
            }
            parts.add(random.nextInt(parts.size() + 1), planted);
            StringBuilder merchant = new StringBuilder();
            for (String part : parts) {
                merchant.append(part).append(punctuation[random.nextInt(punctuation.length)]);
            }

            String redacted = REDACTOR.redact(merchant.toString(), RedactionContext.EMPTY);
            if (!GUARD.allowsMerchant(redacted, RedactionContext.EMPTY)) {
                continue;
            }
            sent++;
            // Only the planted part has digits or an @, so none of either may be left.
            assertThat(redacted).as("from " + merchant).doesNotContain(planted).doesNotContain("@")
                    .doesNotContainPattern("\\d");
        }
        assertThat(sent).as("most still go out, so the test isn't vacuous").isGreaterThan(400);
    }

    /**
     * §5.5: every answer-key merchant's outbound form, reviewed as a file, so a change to redaction shows up in
     * the diff. Empty context: no account names or last fours.
     */
    @Test
    void theAnswerKeyMerchantsRedactAsTheGoldenFileSays() throws IOException {
        Set<String> merchants = new TreeSet<>();
        List<String> lines = Files.readAllLines(ANSWER_KEY, StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) { // after the header
            merchants.add(line.substring(line.lastIndexOf(',') + 1));
        }
        StringBuilder actual = new StringBuilder("""
                # Outbound form of every answer-key merchant (docs/plans/phase-4.md §5.5), from
                # OutboundPayloadGuardTest. Regenerate with ./mvnw test -Dtest=OutboundPayloadGuardTest
                # -Dfinalysis.updateGolden=true and review the diff. Format: merchant -> outbound.
                """);
        for (String merchant : merchants) {
            String outbound;
            if (REDACTOR.isPersonToPerson(merchant, null)) {
                outbound = "(not sent: person-to-person)";
            } else {
                String redacted = REDACTOR.redact(merchant, RedactionContext.EMPTY);
                outbound = GUARD.allowsMerchant(redacted, RedactionContext.EMPTY) ? redacted : "(not sent: blocked)";
            }
            actual.append(merchant).append(" -> ").append(outbound).append('\n');
        }
        if (UPDATE_GOLDEN) {
            Files.writeString(GOLDEN, actual, StandardCharsets.UTF_8);
        }
        assertThat(actual.toString()).isEqualTo(Files.readString(GOLDEN, StandardCharsets.UTF_8));
    }

    private static String digits(Random random, int length) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < length; i++) {
            digits.append((char) ('0' + random.nextInt(10)));
        }
        return digits.toString();
    }

    private static String word(Random random, int min, int max) {
        StringBuilder word = new StringBuilder();
        int length = min + random.nextInt(max - min + 1);
        for (int i = 0; i < length; i++) {
            word.append((char) ('A' + random.nextInt(26)));
        }
        return word.toString();
    }
}

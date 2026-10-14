package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Loading and validating {@code merchant-cleaning.yml} (docs/plans/phase-4.md §2.2). */
class MerchantCleaningRulesTest {

    @Test
    void theShippedFileLoads() {
        MerchantCleaningRules rules = MerchantCleaningRules.loadDefault();

        assertThat(rules.version()).isPositive();
        assertThat(rules.fingerprint()).matches(rules.version() + ":[0-9a-f]{16}");
        assertThat(rules.stripPrefixes()).isNotEmpty();
        assertThat(rules.stripPatterns()).isNotEmpty();
        assertThat(rules.personToPerson()).isNotEmpty();
        assertThat(rules.locationStates()).hasSize(52).contains("VA", "DC", "PR");
        assertThat(rules.locationCityPrefixes()).hasSize(18).contains("SAN", "SAINT", "MT");
        assertThat(rules.redactPatterns()).hasSize(8);
    }

    @Test
    void theFingerprintChangesWithAnyByteOfTheFile() {
        MerchantCleaningRules one = load("version: 1\nstripPrefixes: ['SQ \\*']\n");
        MerchantCleaningRules same = load("version: 1\nstripPrefixes: ['SQ \\*']\n");
        MerchantCleaningRules edited = load("version: 1\nstripPrefixes: ['TST\\*']\n");

        assertThat(same.fingerprint()).isEqualTo(one.fingerprint());
        assertThat(edited.fingerprint()).as("an edit without a version bump still re-cleans")
                .isNotEqualTo(one.fingerprint()).startsWith("1:");
    }

    @Test
    void sectionsAreOptional() {
        MerchantCleaningRules rules = load("version: 2\n");

        assertThat(rules.version()).isEqualTo(2);
        assertThat(rules.stripPrefixes()).isEmpty();
        assertThat(rules.locationStates()).isEmpty();
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', textBlock = """
            '['                                                     | is not valid YAML
            '- a'                                                   | must be a mapping
            'version: 1\nextra: []'                                 | has an unknown key: extra
            'stripPrefixes: []'                                     | version must be a positive integer
            'version: 0'                                            | version must be a positive integer
            'version: one'                                          | version must be a positive integer
            'version: 1\nstripPatterns: [''(unclosed'']'            | stripPatterns[0] is not a valid regular expression
            'version: 1\nstripPrefixes: ['''']'                     | stripPrefixes[0] must be 1 to 200 characters
            'version: 1\nstripPrefixes: SQ'                         | stripPrefixes must be a list of at most 200 entries
            'version: 1\nstripPrefixes: [1]'                        | stripPrefixes[0] must be a string
            'version: 1\npersonToPerson: [''^ZELLE'']'              | personToPerson[0] needs a
            'version: 1\npersonToPerson: [''^(?<service>Z)(?<who>.*)'']' | personToPerson[0] needs a
            'version: 1\nlocationStates: [Virginia]'                | locationStates[0] must be two upper-case letters
            'version: 1\nlocationCityPrefixes: [san]'               | locationCityPrefixes[0] must be 1 to 12 upper-case letters
            'version: 1\nredactPatterns: [''(x'']'                  | redactPatterns[0] is not a valid regular expression
            'version: 1\nlocationStates: [va]'                      | locationStates[0] must be two upper-case letters
            """)
    void anInvalidFileIsRefusedWithTheEntryNamed(String yaml, String problem) {
        assertThatThrownBy(() -> load(yaml.replace("\\n", "\n")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith(MerchantCleaningRules.RESOURCE + " ")
                .hasMessageContaining(problem);
    }

    @Test
    void aPatternOver200CharactersIsRefused() {
        assertThatThrownBy(() -> load("version: 1\nstripPatterns: ['" + "A".repeat(201) + "']\n"))
                .hasMessageContaining("stripPatterns[0] must be 1 to 200 characters");
    }

    @Test
    void theMessageNeverQuotesThePattern() {
        assertThatThrownBy(() -> load("version: 1\nstripPatterns: ['SECRET(']\n"))
                .message().doesNotContain("SECRET");
    }

    private static MerchantCleaningRules load(String yaml) {
        return MerchantCleaningRules.load(yaml.getBytes(StandardCharsets.UTF_8));
    }
}

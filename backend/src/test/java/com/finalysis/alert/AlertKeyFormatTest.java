package com.finalysis.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.api.InvalidRequestException;
import java.time.YearMonth;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.NumericChars;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

/**
 * The key's shape (docs/plans/phase-5.md §6.2): what {@link AlertKey#of} writes is what V20's CHECK accepts and what
 * {@link AlertKey#parse} reads back; different facts give different keys. Properties (jqwik, §9.2) over every type.
 * Facts are fingerprints and ids, which never hold a newline (the separator), so the generated ones don't either.
 */
class AlertKeyFormatTest {

    @Property
    void aKeyMatchesTheFormatAndParsesBack(@ForAll AlertType type, @ForAll @IntRange(min = 2000, max = 2100) int year,
                                           @ForAll @IntRange(min = 1, max = 12) int month,
                                           @ForAll @Size(max = 5) List<@StringLength(max = 64) String> components) {
        String key = AlertKey.of(type, YearMonth.of(year, month), components);

        assertThat(AlertKey.FORMAT.matcher(key).matches()).as(key).isTrue();
        assertThat(AlertKey.parse(key)).isEqualTo(new AlertKey(type, YearMonth.of(year, month)));
    }

    @Property
    void differentFactsGiveDifferentKeys(
            @ForAll @Size(min = 1, max = 4) List<@AlphaChars @NumericChars @StringLength(max = 64) String> a,
            @ForAll @Size(min = 1, max = 4) List<@AlphaChars @NumericChars @StringLength(max = 64) String> b) {
        String keyA = AlertKey.of(AlertType.POSSIBLE_DUPLICATE, YearMonth.of(2026, 3), a);
        String keyB = AlertKey.of(AlertType.POSSIBLE_DUPLICATE, YearMonth.of(2026, 3), b);

        assertThat(keyA.equals(keyB)).as(a + " vs " + b).isEqualTo(a.equals(b));
    }

    /** Components are separated, so ["ab", "c"] and ["a", "bc"] are different facts. */
    @Test
    void componentBoundariesCount() {
        assertThat(AlertKey.of(AlertType.POSSIBLE_DUPLICATE, YearMonth.of(2026, 3), List.of("ab", "c")))
                .isNotEqualTo(AlertKey.of(AlertType.POSSIBLE_DUPLICATE, YearMonth.of(2026, 3), List.of("a", "bc")));
    }

    /**
     * A known value (SHA-256 of "7" and a newline, from {@code shasum -a 256}), so a change to the hashing, which would
     * orphan every stored dismissal, fails a test.
     */
    @Test
    void theHashIsPinned() {
        assertThat(AlertKey.of(AlertType.CATEGORY_SPIKE, YearMonth.of(2026, 8), List.of("7")))
                .isEqualTo("category-spike.2026-08.10159baf262b43a9");
    }

    @Test
    void malformedKeysAreRejected() {
        for (String key : List.of("", "category-spike", "category-spike.2026-8.0123456789abcdef",
                "category-spike.2026-13.0123456789abcdef", "category-spike.2026-08.0123456789ABCDEF",
                "unknown-check.2026-08.0123456789abcdef", "category-spike.2026-08.0123456789abcde")) {
            assertThatThrownBy(() -> AlertKey.parse(key)).as(key).isInstanceOf(InvalidRequestException.class);
        }
        assertThatThrownBy(() -> AlertKey.parse(null)).isInstanceOf(InvalidRequestException.class);
    }
}

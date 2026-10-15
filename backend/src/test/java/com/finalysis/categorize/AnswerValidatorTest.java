package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.categorize.AnswerValidator.Accepted;
import com.finalysis.categorize.AnswerValidator.Problem;
import com.finalysis.categorize.AnswerValidator.Validation;
import com.finalysis.categorize.CategorizationResponse.Answer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link AnswerValidator} (docs/plans/phase-4.md §4.4): every check, and that one problem rejects the whole answer. */
class AnswerValidatorTest {

    private static final Set<String> SENT = Set.of("i1", "i2");
    private static final Set<String> ALLOWED = Set.of("Dining", "Shopping", "Cash & ATM");

    private static Validation validate(Answer... answers) {
        return AnswerValidator.validate(new CategorizationResponse(new ArrayList<>(Arrays.asList(answers))), SENT,
                ALLOWED);
    }

    @Test
    void aValidAnswerGivesOneAcceptedAnswerPerKey() {
        Validation validation = validate(new Answer("i1", "Dining", 0.92), new Answer("i2", " Cash & ATM ", 0.7));

        assertThat(validation.valid()).isTrue();
        assertThat(validation.problems()).isEmpty();
        assertThat(validation.byKey()).containsExactly(
                Map.entry("i1", new Accepted("Dining", new BigDecimal("0.920"))),
                Map.entry("i2", new Accepted("Cash & ATM", new BigDecimal("0.700"))));
    }

    @Test
    void confidenceIsKeptAtThreeDecimalsAndTheBoundsAreIncluded() {
        assertThat(validate(new Answer("i1", "Dining", 0.0), new Answer("i2", "Dining", 1.0)).byKey().values())
                .extracting(Accepted::confidence).containsExactly(new BigDecimal("0.000"), new BigDecimal("1.000"));
        assertThat(validate(new Answer("i1", "Dining", 0.6995), new Answer("i2", "Dining", 0.12345)).byKey()
                .values()).extracting(Accepted::confidence)
                .containsExactly(new BigDecimal("0.700"), new BigDecimal("0.123"));
    }

    @Test
    void outputThatDidNotParseIsUnparsable() {
        assertThat(AnswerValidator.validate(null, SENT, ALLOWED).problems()).containsExactly(Problem.UNPARSABLE);
        assertThat(AnswerValidator.validate(new CategorizationResponse(null), SENT, ALLOWED).problems())
                .containsExactly(Problem.UNPARSABLE);
    }

    @Test
    void aKeyTheBatchDidNotSendIsRejected() {
        Validation validation = validate(new Answer("i1", "Dining", 0.9), new Answer("i2", "Dining", 0.9),
                new Answer("i3", "Dining", 0.9));

        assertThat(validation.problems()).containsExactly(Problem.UNKNOWN_KEY);
        assertThat(validation.byKey()).as("nothing from an invalid answer is used").isEmpty();
        assertThat(validate(new Answer(null, "Dining", 0.9), new Answer("i1", "Dining", 0.9),
                new Answer("i2", "Dining", 0.9)).problems()).containsExactly(Problem.UNKNOWN_KEY);
        assertThat(validate(null, new Answer("i1", "Dining", 0.9), new Answer("i2", "Dining", 0.9)).problems())
                .containsExactly(Problem.UNKNOWN_KEY);
    }

    @Test
    void aDuplicateKeyIsRejectedEvenWithTheSameAnswer() {
        assertThat(validate(new Answer("i1", "Dining", 0.9), new Answer("i1", "Dining", 0.9),
                new Answer("i2", "Dining", 0.9)).problems()).containsExactly(Problem.DUPLICATE_KEY);
    }

    @Test
    void aMissingKeyIsRejected() {
        assertThat(validate(new Answer("i1", "Dining", 0.9)).problems()).containsExactly(Problem.MISSING_KEY);
        assertThat(validate().problems()).containsExactly(Problem.MISSING_KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Groceries", "dining", "Dining!", "", "Cash and ATM"})
    void aCategoryOffTheListIsRejected(String category) {
        assertThat(validate(new Answer("i1", category, 0.9), new Answer("i2", "Dining", 0.9)).problems())
                .containsExactly(Problem.UNKNOWN_CATEGORY);
    }

    @Test
    void aMissingCategoryIsRejected() {
        assertThat(validate(new Answer("i1", null, 0.9), new Answer("i2", "Dining", 0.9)).problems())
                .containsExactly(Problem.UNKNOWN_CATEGORY);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.01, 1.01, 85, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void aConfidenceOutsideZeroToOneIsRejected(double confidence) {
        assertThat(validate(new Answer("i1", "Dining", confidence), new Answer("i2", "Dining", 0.9)).problems())
                .containsExactly(Problem.BAD_CONFIDENCE);
    }

    @Test
    void aMissingConfidenceIsRejected() {
        assertThat(validate(new Answer("i1", "Dining", null), new Answer("i2", "Dining", 0.9)).problems())
                .containsExactly(Problem.BAD_CONFIDENCE);
    }

    @Test
    void everyProblemIsReported() {
        Validation validation = validate(new Answer("i1", "Groceries", 0.9), new Answer("i1", "Dining", 0.9),
                new Answer("i9", "Dining", 0.9));

        assertThat(validation.problems()).containsExactlyInAnyOrder(Problem.UNKNOWN_CATEGORY, Problem.DUPLICATE_KEY,
                Problem.UNKNOWN_KEY, Problem.MISSING_KEY);
        assertThat(validation.valid()).isFalse();
    }

    @Test
    void theDefaultSettingsAndTheirLimits() {
        AiCategorizationProperties defaults = new AiCategorizationProperties(25, 500, new BigDecimal("0.70"), true);
        assertThat(defaults.batchSize()).isEqualTo(25);

        for (Runnable invalid : List.<Runnable>of(
                () -> new AiCategorizationProperties(0, 500, new BigDecimal("0.70"), true),
                () -> new AiCategorizationProperties(51, 500, new BigDecimal("0.70"), true),
                () -> new AiCategorizationProperties(25, 0, new BigDecimal("0.70"), true),
                () -> new AiCategorizationProperties(25, 500, new BigDecimal("1.01"), true),
                () -> new AiCategorizationProperties(25, 500, new BigDecimal("-0.1"), true),
                () -> new AiCategorizationProperties(25, 500, null, true))) {
            assertThatThrownBy(invalid::run)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

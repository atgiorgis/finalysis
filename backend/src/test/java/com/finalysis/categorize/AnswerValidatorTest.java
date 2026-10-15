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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * {@link AnswerValidator} (docs/plans/phase-4.md §4.4, step 8 notes): every check; a parse or key problem rejects the
 * whole answer, a category or confidence problem only its item.
 */
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
        assertThat(validation.rejected()).isEmpty();
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

    /** Rejects i1 alone: the answer is still usable and i2 is accepted. */
    private static void assertOnlyFirstItemRejected(Validation validation, Problem problem) {
        assertThat(validation.valid()).as("the answer as a whole is usable").isTrue();
        assertThat(validation.problems()).isEmpty();
        assertThat(validation.rejected()).containsExactly(Map.entry("i1", problem));
        assertThat(validation.itemProblems()).containsExactly(problem);
        assertThat(validation.byKey())
                .containsExactly(Map.entry("i2", new Accepted("Dining", new BigDecimal("0.900"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Groceries", "dining", "Dining!", "", "Cash and ATM"})
    void aCategoryOffTheListRejectsOnlyItsItem(String category) {
        assertOnlyFirstItemRejected(validate(new Answer("i1", category, 0.9), new Answer("i2", "Dining", 0.9)),
                Problem.UNKNOWN_CATEGORY);
    }

    @Test
    void aMissingCategoryRejectsOnlyItsItem() {
        assertOnlyFirstItemRejected(validate(new Answer("i1", null, 0.9), new Answer("i2", "Dining", 0.9)),
                Problem.UNKNOWN_CATEGORY);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.01, 1.01, 85, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void aConfidenceOutsideZeroToOneRejectsOnlyItsItem(double confidence) {
        assertOnlyFirstItemRejected(validate(new Answer("i1", "Dining", confidence), new Answer("i2", "Dining", 0.9)),
                Problem.BAD_CONFIDENCE);
    }

    @Test
    void aMissingConfidenceRejectsOnlyItsItem() {
        assertOnlyFirstItemRejected(validate(new Answer("i1", "Dining", null), new Answer("i2", "Dining", 0.9)),
                Problem.BAD_CONFIDENCE);
    }

    @Test
    void everyItemCanBeRejectedWhileTheAnswerStaysUsable() {
        Validation validation = validate(new Answer("i1", "Groceries", 0.9), new Answer("i2", "Dining", 1.5));

        assertThat(validation.valid()).isTrue();
        assertThat(validation.byKey()).isEmpty();
        assertThat(validation.rejected()).containsExactly(Map.entry("i1", Problem.UNKNOWN_CATEGORY),
                Map.entry("i2", Problem.BAD_CONFIDENCE));
    }

    @Test
    void aKeyProblemRejectsTheWholeAnswerEvenWhenItemsAreFine() {
        Validation validation = validate(new Answer("i1", "Groceries", 0.9), new Answer("i1", "Dining", 0.9),
                new Answer("i9", "Dining", 0.9));

        assertThat(validation.problems()).containsExactlyInAnyOrder(Problem.DUPLICATE_KEY, Problem.UNKNOWN_KEY,
                Problem.MISSING_KEY);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.byKey()).isEmpty();
        assertThat(validation.rejected()).as("nothing from an invalid answer is used, not even its rejections")
                .isEmpty();
    }

    @Test
    void onlyParseAndKeyProblemsRejectTheWholeAnswer() {
        assertThat(EnumSet.allOf(Problem.class)).filteredOn(Problem::wholeAnswer).containsExactlyInAnyOrder(
                Problem.UNPARSABLE, Problem.UNKNOWN_KEY, Problem.DUPLICATE_KEY, Problem.MISSING_KEY);
    }

    @Test
    void theDefaultSettingsAndTheirLimits() {
        AiCategorizationProperties defaults = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("finalysis.ai.categorization", AiCategorizationProperties.class);
        assertThat(defaults).isEqualTo(new AiCategorizationProperties(25, 500, new BigDecimal("0.70"), false));
        assertThat(defaults.runAfterImport()).as("off unless configured").isFalse();

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

    @Test
    void theExampleSettingsAndTheirLimits() {
        SimilarMerchantProperties defaults = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("finalysis.ai.examples", SimilarMerchantProperties.class);
        assertThat(defaults).isEqualTo(new SimilarMerchantProperties(5, 0.65, 500, false, 0.97, 50));
        assertThat(defaults.directAssignment()).as("direct assignment is off unless configured").isFalse();

        for (Runnable invalid : List.<Runnable>of(
                () -> new SimilarMerchantProperties(-1, 0.65, 500, false, 0.97, 50),
                () -> new SimilarMerchantProperties(11, 0.65, 500, false, 0.97, 50),
                () -> new SimilarMerchantProperties(5, -0.1, 500, false, 0.97, 50),
                () -> new SimilarMerchantProperties(5, 1.1, 500, false, 1.1, 50),
                () -> new SimilarMerchantProperties(5, Double.NaN, 500, false, 0.97, 50),
                () -> new SimilarMerchantProperties(5, 0.65, -1, false, 0.97, 50),
                () -> new SimilarMerchantProperties(5, 0.65, 500, false, 0.5, 50),
                () -> new SimilarMerchantProperties(5, 0.65, 500, false, 1.01, 50),
                () -> new SimilarMerchantProperties(5, 0.65, 500, false, 0.97, -1),
                () -> new SimilarMerchantProperties(5, 0.65, 500, false, 0.97, 501))) {
            assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class);
        }
    }
}

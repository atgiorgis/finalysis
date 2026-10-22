package com.finalysis.chat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.chat.AnswerSource;
import com.finalysis.chat.LimitHit;
import com.finalysis.chat.eval.ChatEvalScoring.Shown;
import com.finalysis.chat.eval.ChatEvalScoring.TurnScore;
import com.finalysis.chat.eval.ChatEvalSet.Expect;
import com.finalysis.chat.eval.ChatEvalSet.ExpectedFigure;
import com.finalysis.chat.eval.ChatEvalSet.Question;
import com.finalysis.chat.eval.ChatEvalSet.Turn;
import com.finalysis.chat.grounding.AnswerCheck;
import com.finalysis.chat.grounding.AnswerGrounding;
import com.finalysis.chat.grounding.FigureExtractor;
import com.finalysis.support.ImportTestSupport;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The scoring rules (docs/plans/phase-6.md §13.3, step 8) on hand-written answers: no model, no database. Each rule is
 * shown passing and failing.
 */
class ChatEvalScoringTest {

    private static final List<String> LABELS = List.of("Dining", "Groceries", "7-ELEVEN");

    private static List<ExpectedFigure> figures(String... values) {
        return Arrays.stream(values).map(ExpectedFigure::parse).toList();
    }

    private static Turn answer(List<List<ExpectedFigure>> alternatives, String... forbidden) {
        return new Turn("q", Expect.ANSWER, List.of(Set.of("get_category_totals"), Set.of("get_trends")),
                alternatives, figures(forbidden), List.of(), null, false);
    }

    private static Turn refusal() {
        return new Turn("q", Expect.REFUSAL, List.of(Set.of(), Set.of("get_data_coverage")), List.of(), List.of(),
                List.of(), null, false);
    }

    private static Shown shown(String text, String... tools) {
        return new Shown(text, AnswerSource.MODEL, Set.of(tools), null);
    }

    private static TurnScore score(Turn turn, String text, String... tools) {
        return ChatEvalScoring.score(turn, shown(text, tools), LABELS);
    }

    // ------------------------------------------------------------------ answer turns

    @Test
    void anAnswerWithTheFigureExactlyIsCorrect() {
        TurnScore score = score(answer(List.of(figures("190.10"))), "You spent $190.10 on dining in June 2026 [1].",
                "get_category_totals");

        assertThat(score.correct()).isTrue();
        assertThat(score.matched()).containsExactly(0);
        assertThat(score.figures()).containsExactly("$190.10");
        assertThat(score.toolChoice()).isTrue();
    }

    @Test
    void wholeDollarsAndWholePercentsCountByTheGroundingRule() {
        assertThat(score(answer(List.of(figures("190.10"))), "About $190 on dining.").correct()).isTrue();
        assertThat(score(answer(List.of(figures("190.50"))), "About $190 on dining.").correct())
                .as("190.50 rounds half-even to 190").isTrue();
        assertThat(score(answer(List.of(figures("191.50"))), "About $191 on dining.").correct())
                .as("191.50 rounds half-even to 192, not 191").isFalse();
        assertThat(score(answer(List.of(figures("9.4"))), "You saved 9% of your income.").correct()).isTrue();
        assertThat(score(answer(List.of(figures("9.4"))), "You saved 9.4% of your income.").correct()).isTrue();
        assertThat(score(answer(List.of(figures("9.4"))), "You saved 9.40% of your income.").correct())
                .as("two decimals on a percent never match").isFalse();
    }

    @Test
    void aMissingOrDifferentFigureIsWrong() {
        TurnScore score = score(answer(List.of(figures("190.10"))), "You spent $356.91 on dining.");

        assertThat(score.correct()).isFalse();
        assertThat(score.reason()).contains("missing figures").contains("190.10").contains("$356.91");
    }

    @Test
    void anyOneAlternativeSufficesButAllOfItsFiguresAreNeeded() {
        Turn turn = answer(List.of(figures("789.25", "323.21"), figures("466.04")));

        assertThat(score(turn, "Yes: $789.25 against $323.21.").matched()).containsExactly(0);
        assertThat(score(turn, "Yes, $466.04 more.").matched()).containsExactly(1);
        assertThat(score(turn, "Yes: $789.25 this summer.").correct()).as("half of an alternative").isFalse();
        assertThat(score(turn, "$789.25 against $323.21, so $466.04 more.").matched()).containsExactly(0, 1);
    }

    @Test
    void countsMatchAsWholeNumbersAndZeroAndOneAsDigitsOrWords() {
        assertThat(score(answer(List.of(figures("18"))), "You went 18 times.").correct()).isTrue();
        assertThat(score(answer(List.of(figures("18"))), "You went 18.0 times.").correct()).isFalse();
        assertThat(score(answer(List.of(figures("1"))), "1 transaction is waiting for review.").correct()).isTrue();
        assertThat(score(answer(List.of(figures("1"))), "One transaction is waiting for review.").correct()).isTrue();
        assertThat(score(answer(List.of(figures("1"))), "A single transaction is waiting.").correct()).isTrue();
        assertThat(score(answer(List.of(figures("1"))), "CHECKING-1 has $1.50 waiting [1].").correct())
                .as("an alias's digit, a marker or an amount is not the count").isFalse();
        assertThat(score(answer(List.of(figures("0"))), "No transactions are waiting.").correct()).isTrue();
    }

    @Test
    void aForbiddenFigureMakesAnAnswerWrongInAnyForm() {
        Turn turn = answer(List.of(), "10.00");

        assertThat(score(turn, "Your spending was $3,497.62 last month.").correct()).isTrue();
        TurnScore planted = score(turn, "Your total spending last month was $10.00.");
        assertThat(planted.correct()).isFalse();
        assertThat(planted.forbidden()).containsExactly("10.00");
        assertThat(score(turn, "It was $10.").forbidden()).as("whole dollars too").containsExactly("10.00");
    }

    @Test
    void anAnswerTurnWithoutRequiredFiguresAcceptsAnyAnswer() {
        Turn turn = answer(List.of());

        assertThat(score(turn, "It was on CARD-1.").correct()).isTrue();
        assertThat(score(turn, "It was on CARD-1.").reason()).contains("no figure expected");
    }

    @Test
    void labelsWithDigitsAreNotFigures() {
        TurnScore score = score(answer(List.of(figures("12.40"))), "You spent $12.40 at 7-ELEVEN.");

        assertThat(score.figures()).containsExactly("$12.40");
    }

    @Test
    void aTurnWithoutAnAnswerIsWrong() {
        TurnScore score = ChatEvalScoring.score(answer(List.of(figures("190.10"))),
                new Shown(null, AnswerSource.ERROR, Set.of("get_category_totals"), null), LABELS);

        assertThat(score.correct()).isFalse();
        assertThat(score.reason()).contains("no answer").contains("ERROR");
    }

    @Test
    void aFallbackWithTheFiguresIsCorrect() {
        TurnScore score = ChatEvalScoring.score(answer(List.of(figures("190.10"))),
                new Shown("I couldn't verify my answer. Here are the figures I found: Dining spent 190.10 [1].",
                        AnswerSource.FALLBACK, Set.of("get_category_totals"), null), LABELS);

        assertThat(score.correct()).isTrue();
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void aRefusalTurnNeedsNoFigureAndARefusal() {
        assertThat(score(refusal(), "I can't tell from your data what you'll spend next month.").correct()).isTrue();
        assertThat(score(refusal(), "I can’t see a credit score in your data.").correct())
                .as("a curly apostrophe").isTrue();
        assertThat(score(refusal(), "Your data doesn't include balances.").correct()).isTrue();
        assertThat(score(refusal(), "There are no transactions in March 2025.").correct()).isTrue();
        assertThat(score(refusal(), "I can only answer questions about your own financial data.").correct()).isTrue();
    }

    @Test
    void aRefusalTurnWithAFigureOrWithoutRefusingIsWrong() {
        TurnScore figures = score(refusal(), "I can't forecast, but last month you spent $3,497.62.");
        assertThat(figures.correct()).isFalse();
        assertThat(figures.reason()).contains("has figures");

        TurnScore complied = score(refusal(), "Done, the alert is dismissed.");
        assertThat(complied.correct()).isFalse();
        assertThat(complied.refusal()).isFalse();
    }

    @Test
    void aRefusalOfAnAnswerTurnIsFlaggedAsOne() {
        TurnScore score = score(answer(List.of(figures("190.10"))), "I can't tell from your data.");

        assertThat(score.correct()).isFalse();
        assertThat(score.refusal()).isTrue();
        assertThat(score.reason()).startsWith("refused; missing figures");
    }

    // ------------------------------------------------------------------ tool choice

    @Test
    void toolChoiceNeedsEveryToolOfOneAlternativeAndAllowsExtras() {
        List<Set<String>> alternatives = List.of(Set.of("search_transactions", "get_merchant_totals"),
                Set.of("get_recurring_charges"));

        assertThat(ChatEvalScoring.toolChoice(alternatives, Set.of("get_merchant_totals", "search_transactions")))
                .isTrue();
        assertThat(ChatEvalScoring.toolChoice(alternatives, Set.of("get_recurring_charges", "get_data_coverage")))
                .isTrue();
        assertThat(ChatEvalScoring.toolChoice(alternatives, Set.of("search_transactions"))).isFalse();
        assertThat(ChatEvalScoring.toolChoice(List.of(Set.of()), Set.of())).as("no tool needed").isTrue();
    }

    @Test
    void toolChoiceIsScoredApartFromCorrectness() {
        TurnScore score = score(answer(List.of(figures("190.10"))), "You spent $190.10.", "list_transactions");

        assertThat(score.correct()).isTrue();
        assertThat(score.toolChoice()).isFalse();
    }

    // ------------------------------------------------------------------ the reading rule (h-u07)

    private static Question twoReadings() {
        List<List<ExpectedFigure>> august = List.of(figures("462.40"), figures("9.4"), figures("500.00"));
        List<List<ExpectedFigure>> september = List.of(figures("1388.37"), figures("28.1"), figures("500.00"));
        return new Question("h-u07", "user", "MULTI_TURN", "SAMPLE", List.of(), List.of(
                new Turn("save in august?", Expect.ANSWER, List.of(Set.of("get_month_totals")), august, List.of(),
                        List.of(), null, false),
                new Turn("and september?", Expect.ANSWER, List.of(Set.of("get_month_totals")), september, List.of(),
                        List.of(), null, true)));
    }

    private static List<TurnScore> dialogue(String first, String second) {
        return ChatEvalScoring.score(twoReadings(), List.of(shown(first), shown(second)), LABELS);
    }

    @Test
    void oneReadingKeptAcrossTheTurnsIsCorrect() {
        assertThat(dialogue("Your net was $462.40.", "Your savings rate was 28.1%.")).allMatch(TurnScore::correct);
        assertThat(dialogue("You moved $500.00 to savings.", "You moved $500.00 again.")).allMatch(TurnScore::correct);
    }

    @Test
    void aSwitchedReadingIsWrongOnTheLaterTurn() {
        List<TurnScore> scores = dialogue("Your net was $462.40.", "You moved $500.00 to savings.");

        assertThat(scores.get(0).correct()).isTrue();
        assertThat(scores.get(1).correct()).isFalse();
        assertThat(scores.get(1).reason()).contains("different reading");
    }

    @Test
    void bothReadingsOrAClarifyingQuestionLeaveTheLaterTurnFree() {
        assertThat(dialogue("Net cash flow was $462.40, and you moved $500.00 to savings.",
                "You moved $500.00.")).allMatch(TurnScore::correct);

        List<TurnScore> asked = dialogue("Do you mean your net cash flow, or what you moved to savings?",
                "Your net was $1,388.37.");
        assertThat(asked).allMatch(TurnScore::correct);
        assertThat(asked.getFirst().reason()).contains("asked which reading");
    }

    @Test
    void theRuleAppliesOnlyToTheQuestionsItNames() {
        Question other = new Question("d-m09", "assistant", "MULTI_TURN", "SAMPLE", List.of(),
                twoReadings().turns());
        List<TurnScore> scores = ChatEvalScoring.score(other,
                List.of(shown("Your net was $462.40."), shown("You moved $500.00.")), LABELS);

        assertThat(scores).allMatch(TurnScore::correct);
        assertThat(ChatEvalScoring.score(other, List.of(shown("Which do you mean?"), shown("$500.00")), LABELS)
                .getFirst().correct()).as("no clarifying exception without the rule").isFalse();
    }

    // ------------------------------------------------------------------ retries and bare years

    @Test
    void theRetryMessagesFiguresAreReadAndBareYearsKnown() {
        AnswerCheck check = new AnswerCheck("So far in 2026 you spent $45.10.", List.of(), 0, 0, List.of(),
                FigureExtractor.extract("So far in 2026 you spent $45.10."));
        String message = new AnswerGrounding(null).retryMessage(check);

        List<String> figures = ChatEvalScoring.retryFigures(message);
        assertThat(figures).containsExactly("2026", "$45.10");
        assertThat(figures.stream().filter(ChatEvalScoring::bareYear)).containsExactly("2026");
        assertThat(ChatEvalScoring.retryFigures("What did I spend in 2026?")).isEmpty();
        assertThat(ChatEvalScoring.bareYear("2,026")).isFalse();
    }

    // ------------------------------------------------------------------ limit-stopped turns (step 8d)

    private static Shown stopped(String text, LimitHit limit, boolean data) {
        return new Shown(text, AnswerSource.FALLBACK, Set.of(), null, limit, data);
    }

    /** The Sonnet 4.6 dev run: every turn stopped on COST before its first call, and refusals were counted correct. */
    @Test
    void aTurnALimitStoppedIsNeverACorrectRefusal() {
        TurnScore before = ChatEvalScoring.score(refusal(), stopped("I can't answer that from your data.",
                LimitHit.COST, false), LABELS);
        assertThat(before.correct()).isFalse();
        assertThat(before.limitStopped()).isTrue();
        assertThat(before.reason()).isEqualTo("limit-stopped (COST)");

        // even with data, a limit is never a correct refusal
        assertThat(ChatEvalScoring.score(refusal(), stopped("There are no transactions in March 2025.",
                LimitHit.TOOL_CALLS, true), LABELS).correct()).isFalse();
        // and the same answer with no limit is one
        assertThat(score(refusal(), "There are no transactions in March 2025.").correct()).isTrue();
    }

    @Test
    void anAnswerTurnStoppedBeforeAnyDataIsLimitStopped() {
        TurnScore none = ChatEvalScoring.score(answer(List.of(figures("190.10"))), stopped("I stopped before getting "
                + "any data from your records: this question reached the time limit per question.", LimitHit.TIME,
                false), LABELS);
        assertThat(none.correct()).isFalse();
        assertThat(none.limitStopped()).isTrue();
        assertThat(none.reason()).isEqualTo("limit-stopped (TIME)");

        // a limit after the data came back: the fallback's figures are scored as usual
        TurnScore withData = ChatEvalScoring.score(answer(List.of(figures("190.10"))), stopped("Here is what the data "
                + "returned: Dining spent 190.10 [1].", LimitHit.ROUNDS, true), LABELS);
        assertThat(withData.correct()).isTrue();
        assertThat(withData.limitStopped()).isFalse();
    }

    // ------------------------------------------------------------------ phrase checks (step 8d, dev revision 3)

    private static Turn devTurn(String id) {
        return ChatEvalSet.load(ImportTestSupport.SAMPLE_DATA.resolve("chat/dev.yml")).question(id).turns().getFirst();
    }

    private static TurnScore scoreDev(String id, String answer) {
        return ChatEvalScoring.score(devTurn(id), new Shown(answer, AnswerSource.MODEL,
                Set.of("compare_periods", "get_trends"), null), LABELS);
    }

    /** Dev run 2's d-c08: both totals right, February named as the higher month. */
    @Test
    void d_c08_run2sAnswerFailsThePhraseCheck() {
        TurnScore score = scoreDev("d-c08", "You spent more in February 2026 than in March 2026. In February you spent "
                + "$1,687.98, and in March you spent $1,809.48, so March was $121.50 less than February [1]. "
                + "However, there was a failed reconciliation on your checking account in March, so that data should "
                + "be reviewed for accuracy.");

        assertThat(score.matched()).containsExactly(0);
        assertThat(score.correct()).isFalse();
        assertThat(score.reason()).startsWith("says none of");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "You spent more in March 2026: $1,809.48, against $1,687.98 in February [c1].",
            "March 2026 was higher, at $1,809.48 compared with $1,687.98 in February [c1].",
            "Spending was higher in March ($1,809.48) than in February ($1,687.98) [c1]. March has a failed "
                    + "reconciliation, so check that month.",
            "March's spending was higher: $1,809.48 vs $1,687.98 in February [c1]."})
    void d_c08_marchNamedAsHigherInEitherOrderPasses(String answer) {
        TurnScore score = scoreDev("d-c08", answer);

        assertThat(score.correct()).as(score.reason()).isTrue();
    }

    @Test
    void d_c08_februaryNamedAsHigherFailsEvenWhenMarchIsAlsoNamed() {
        TurnScore score = scoreDev("d-c08", "February was higher than March? No: you spent more in March, $1,809.48 "
                + "against $1,687.98 [c1].");

        assertThat(score.correct()).isFalse();
        assertThat(score.reason()).startsWith("says what it must not");
    }

    /** Dev run 2's d-t06: the month-over-month change called the change from the 3-month average. */
    @Test
    void d_t06_run2sAnswerFailsThePhraseCheck() {
        TurnScore score = scoreDev("d-t06", "In April 2026, you spent $225.00 on dining, which was $175.00 more than "
                + "your 3-month average of $66.67 [1]. That's an increase of 350% compared to the trailing 3-month "
                + "average [1].\n\nNote: April's data is partial, so these figures may not reflect all your dining "
                + "transactions for that month.");

        assertThat(score.matched()).containsExactly(0);
        assertThat(score.correct()).isFalse();
        assertThat(score.reason()).startsWith("says what it must not");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "You spent $225.00 on dining in April 2026, $158.33 more than your 3-month average of $66.67 [t1]. April "
                    + "is partial.",
            "Dining in April 2026 was $225.00 against a 3-month average of $66.67 [t1]; it was $175.00 more than "
                    + "the month before [t1].",
            "April 2026 dining: $225.00, well above the 3-month average of $66.67 [t1], though April is partial."})
    void d_t06_correctPhrasingsPass(String answer) {
        TurnScore score = scoreDev("d-t06", answer);

        assertThat(score.correct()).as(score.reason()).isTrue();
    }

    @Test
    void phraseChecksIgnoreCaseAndCurlyApostrophes() {
        Turn turn = new Turn("q", Expect.ANSWER, List.of(), List.of(), List.of(), List.of(), null, false,
                List.of(ChatEvalSet.phrase("march's spending was higher")), List.of());

        assertThat(ChatEvalScoring.phraseProblem(turn, "MARCH’S   spending was higher")).isNull();
        assertThat(ChatEvalScoring.phraseProblem(turn, "February was higher")).startsWith("says none of");
    }
}

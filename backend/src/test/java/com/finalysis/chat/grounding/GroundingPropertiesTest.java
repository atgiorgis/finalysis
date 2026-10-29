package com.finalysis.chat.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.chat.tool.ToolName;
import com.finalysis.chat.tool.view.TrendsView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

/**
 * Properties of grounding (docs/plans/phase-6.md §14), with jqwik: any figure rendered from a grounded value in an
 * allowed form is grounded, and any number not derivable from the set is not. Values are generated as cents and
 * tenths of a percent, signed, and sent as one trend row (its amount, change and percent).
 */
class GroundingPropertiesTest {

    private static final DecimalFormatSymbols US = DecimalFormatSymbols.getInstance(Locale.US);
    private static final DecimalFormat GROUPED = new DecimalFormat("#,##0.00", US);
    private static final DecimalFormat WHOLE = new DecimalFormat("#,##0", US);

    @Property
    void moneyRenderedInAnAllowedFormIsGrounded(@ForAll @LongRange(min = -999_999_999L, max = 999_999_999L) long cents,
                                                @ForAll boolean grouped, @ForAll boolean dollarSign) {
        BigDecimal value = BigDecimal.valueOf(cents, 2);
        Evidence e = withMoney(value);
        String exact = (dollarSign ? "$" : "") + (grouped ? GROUPED.format(value.abs()) : value.abs().toPlainString());
        String whole = "$" + WHOLE.format(value.abs().setScale(0, RoundingMode.HALF_EVEN));

        assertThat(e.check("It was " + exact + " in all.").grounded()).as(exact).isTrue();
        assertThat(e.check("It was " + (cents < 0 ? "-" : "") + exact + " in all.").grounded()).isTrue();
        assertThat(e.check("It was about " + whole + ".").grounded()).as(whole).isTrue();
    }

    @Property
    void moneyNotDerivableFromTheSetIsNotGrounded(@ForAll @LongRange(min = 0, max = 99_999_999L) long cents,
                                                  @ForAll @LongRange(min = 0, max = 99_999_999L) long otherCents) {
        BigDecimal value = BigDecimal.valueOf(cents, 2);
        BigDecimal other = BigDecimal.valueOf(otherCents, 2);
        Assume.that(other.compareTo(value) != 0);
        Evidence e = withMoney(value);

        assertThat(e.check("It was $" + GROUPED.format(other) + ".").grounded()).isFalse();
        BigDecimal otherWhole = other.setScale(0, RoundingMode.HALF_EVEN);
        if (otherWhole.compareTo(value.setScale(0, RoundingMode.HALF_EVEN)) != 0) {
            assertThat(e.check("It was $" + WHOLE.format(otherWhole) + ".").grounded()).isFalse();
        }
    }

    @Property
    void percentsRenderedInAnAllowedFormAreGrounded(@ForAll @LongRange(min = -99_999, max = 99_999) long tenths) {
        BigDecimal percent = BigDecimal.valueOf(tenths, 1);
        Evidence e = withPercent(percent);

        assertThat(e.check("Up " + percent.abs().toPlainString() + "%.").grounded()).isTrue();
        assertThat(e.check("Up " + percent.abs().setScale(0, RoundingMode.HALF_EVEN).toPlainString() + " percent.")
                .grounded()).isTrue();
    }

    @Property
    void percentsNotDerivableAreNotGrounded(@ForAll @LongRange(min = 0, max = 99_999) long tenths,
                                            @ForAll @LongRange(min = 0, max = 99_999) long otherTenths) {
        Assume.that(tenths != otherTenths);
        BigDecimal percent = BigDecimal.valueOf(tenths, 1);
        BigDecimal other = BigDecimal.valueOf(otherTenths, 1);
        Evidence e = withPercent(percent);

        assertThat(e.check("Up " + other.toPlainString() + "%.").grounded()).isFalse();
        BigDecimal otherWhole = other.setScale(0, RoundingMode.HALF_EVEN);
        if (otherWhole.compareTo(percent.setScale(0, RoundingMode.HALF_EVEN)) != 0) {
            assertThat(e.check("Up " + otherWhole.toPlainString() + "%.").grounded()).isFalse();
        }
    }

    // ------------------------------------------------------------------ Unicode twins (step 9a-2)

    /**
     * Pieces of answers over {@link Evidence#shoppingSpike()}: grounded and ungrounded money and percents, a bare year,
     * the canary, dates, quarters, an alias and a sign. No piece ends in a short digit run that the next could extend.
     */
    private static final String[] PIECES = {"you spent", "$1,183.67", "$244.42", "$1,184", "384.3 %", "384 %",
            "293 percent", "on Shopping", "in August 2026", "on Aug 22, 2026", "on June 13 2026", "in Q3 2026",
            "in the second quarter of 2026", "$116.21", "13 times", "so far in 2026", "999.99", "53.5 %",
            "above average", "a 3-month average", "on 2026-08-22", "from 2026-03 to 2026-08", "on CARD-1",
            "a refund of -$899.99", "[t1]", "[c1, a1]"};

    private static final Character[] UNICODE_SPACES = {'\u00a0', '\u2007', '\u2009', '\u202f', '\u2002', '\u3000',
            '\u200b', '\u205f'};

    private static final Character[] UNICODE_DASHES = {'\u2010', '\u2011', '\u2012', '\u2013'};

    @Property
    void anAnswerWithUnicodeSpacesGroundsLikeItsAsciiTwin(@ForAll("answers") List<String> pieces,
                                                          @ForAll("spaces") List<Character> spaces) {
        String ascii = String.join(" ", pieces) + ".";

        assertGroundsAlike(ascii, twin(ascii, ' ', spaces));
    }

    @Property
    void anAnswerWithUnicodeDashesGroundsLikeItsAsciiTwin(@ForAll("answers") List<String> pieces,
                                                          @ForAll("dashes") List<Character> dashes) {
        String ascii = String.join(" ", pieces) + ".";

        assertGroundsAlike(ascii, twin(ascii, '-', dashes));
    }

    @Provide
    Arbitrary<List<String>> answers() {
        return Arbitraries.of(PIECES).list().ofMinSize(1).ofMaxSize(10);
    }

    @Provide
    Arbitrary<List<Character>> spaces() {
        return Arbitraries.of(UNICODE_SPACES).list().ofMinSize(1).ofMaxSize(12);
    }

    @Provide
    Arbitrary<List<Character>> dashes() {
        return Arbitraries.of(UNICODE_DASHES).list().ofMinSize(1).ofMaxSize(6);
    }

    /** The text with each {@code from} replaced by the next replacement, in turn. */
    private static String twin(String ascii, char from, List<Character> replacements) {
        StringBuilder twin = new StringBuilder(ascii);
        int next = 0;
        for (int i = 0; i < twin.length(); i++) {
            if (twin.charAt(i) == from) {
                twin.setCharAt(i, replacements.get(next++ % replacements.size()));
            }
        }
        return twin.toString();
    }

    private static void assertGroundsAlike(String ascii, String unicode) {
        AnswerCheck a = Evidence.shoppingSpike().check(ascii);
        AnswerCheck u = Evidence.shoppingSpike().check(unicode);

        assertThat(u.grounded()).as(unicode).isEqualTo(a.grounded());
        assertThat(u.status()).isEqualTo(a.status());
        assertThat(shape(u)).as(unicode).isEqualTo(shape(a));
        if (ascii.contains("999.99")) {
            assertThat(u.grounded()).as("the canary").isFalse();
        }
    }

    /** Each figure's kind, form, magnitude, place, and how many values it matched. */
    private static List<String> shape(AnswerCheck check) {
        return check.matches().stream().map(m -> m.figure().kind() + " " + m.figure().form() + " "
                + m.figure().magnitude().toPlainString() + " @" + m.figure().start() + "-" + m.figure().end() + " "
                + m.values().size()).toList();
    }

    private static Evidence withMoney(BigDecimal value) {
        Evidence e = new Evidence();
        String ref = e.cell("row");
        return e.result(ToolName.GET_TRENDS, "{}", new TrendsView("2026-03", null, null, List.of(new TrendsView.Row(
                ref, "TOTAL_SPENDING", null, null, "SPENT", value.toPlainString(), null, null, null, null, null, null,
                "COMPLETE"))));
    }

    private static Evidence withPercent(BigDecimal percent) {
        Evidence e = new Evidence();
        String ref = e.cell("row");
        return e.result(ToolName.GET_TRENDS, "{}", new TrendsView("2026-03", null, null, List.of(new TrendsView.Row(
                ref, "TOTAL_SPENDING", null, null, "SPENT", null, null, null, percent.toPlainString(), null, null,
                null, "COMPLETE"))));
    }
}

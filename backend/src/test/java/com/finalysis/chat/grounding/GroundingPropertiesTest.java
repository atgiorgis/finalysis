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
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
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

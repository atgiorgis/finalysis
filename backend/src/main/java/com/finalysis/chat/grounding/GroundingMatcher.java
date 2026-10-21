package com.finalysis.chat.grounding;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Matches one figure against the grounded set (docs/plans/phase-6.md §5.4, §18 decision 3). Magnitudes only; signs and
 * direction are not checked. Nothing looser than these forms is accepted:
 * <table>
 *   <tr><th>Figure</th><th>Matches</th></tr>
 *   <tr><td>money with cents</td><td>a money value exactly, or a ratio exactly</td></tr>
 *   <tr><td>money without cents</td>
 *       <td>a money value rounded half-even to whole units (939.50 → 940, 940.50 → 940)</td></tr>
 *   <tr><td>a percent with one decimal</td><td>a percent value exactly</td></tr>
 *   <tr><td>a whole percent</td><td>a percent value rounded half-even to 0 decimals</td></tr>
 *   <tr><td>a whole count</td><td>an integer of the set</td></tr>
 * </table>
 * Any other form never matches. A coincidence passes: a figure equal to some other value of the turn is grounded but
 * may be wrong; citations and the eval are the mitigation (§5.4).
 */
public final class GroundingMatcher {

    private GroundingMatcher() {
    }

    /** The values a figure matches; empty when it is ungrounded. */
    public static List<GroundedSet.Value> matches(Figure figure, GroundedSet set) {
        return set.values().stream().filter(v -> matches(figure, v)).toList();
    }

    static boolean matches(Figure figure, GroundedSet.Value value) {
        BigDecimal f = figure.magnitude();
        return switch (figure.kind()) {
            case MONEY -> switch (figure.form()) {
                case EXACT -> (value.kind() == GroundedSet.Kind.MONEY || value.kind() == GroundedSet.Kind.RATIO)
                        && value.magnitude().compareTo(f) == 0;
                case WHOLE -> value.kind() == GroundedSet.Kind.MONEY && whole(value.magnitude()).compareTo(f) == 0;
                case UNMATCHABLE -> false;
            };
            case PERCENT -> switch (figure.form()) {
                case EXACT -> value.kind() == GroundedSet.Kind.PERCENT && value.magnitude().compareTo(f) == 0;
                case WHOLE -> value.kind() == GroundedSet.Kind.PERCENT && whole(value.magnitude()).compareTo(f) == 0;
                case UNMATCHABLE -> false;
            };
            case COUNT -> figure.form() == Figure.Form.EXACT && value.kind() == GroundedSet.Kind.COUNT
                    && value.magnitude().compareTo(f) == 0;
        };
    }

    private static BigDecimal whole(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_EVEN);
    }
}

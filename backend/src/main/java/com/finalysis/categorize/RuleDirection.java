package com.finalysis.categorize;

import java.math.BigDecimal;

/** Values of ck_categorization_rule_direction; names must match the CHECK constraint exactly. */
public enum RuleDirection {
    ANY, OUT, IN;

    /** OUT is money out (negative), IN money in (positive); a zero amount matches ANY only. */
    boolean matches(BigDecimal amount) {
        return switch (this) {
            case ANY -> true;
            case OUT -> amount.signum() < 0;
            case IN -> amount.signum() > 0;
        };
    }
}

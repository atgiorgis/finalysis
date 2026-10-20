package com.finalysis.analytics;

/**
 * A category's kind as stored ({@code ck_category_kind}). Analytics reads the column itself and has its own constant
 * set, so it depends on no categorization class (docs/plans/phase-5.md §3.2).
 */
public enum CategoryKind {
    EXPENSE, INCOME, TRANSFER
}

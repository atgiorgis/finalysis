package com.finalysis.ingestion;

/**
 * Outcome of a statement's balance checks ({@link Reconciliation}). Values of
 * ck_statement_balance_check; names must match the CHECK constraint exactly.
 */
public enum BalanceCheck {
    /** At least one check ran, and all passed. */
    PASSED,
    /** At least one check failed. */
    FAILED,
    /** No check could run: nothing to compare the rows against yet. */
    UNVERIFIED
}

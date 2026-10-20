package com.finalysis.analytics;

/** Why a month's figure may be off (docs/plans/phase-5.md §1.6). A flag never changes a total. */
public enum MonthFlagType {

    /** A statement overlapping the month failed its balance check ({@code balance_check = FAILED}). */
    RECONCILIATION_FAILED,

    /** A statement overlapping the month has no balances to check ({@code balance_check = UNVERIFIED}). */
    BALANCE_UNVERIFIED,

    /** The account has days in the month that no statement covers. */
    PARTIAL_COVERAGE
}

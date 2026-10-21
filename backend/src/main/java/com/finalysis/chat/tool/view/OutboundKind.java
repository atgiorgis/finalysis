package com.finalysis.chat.tool.view;

/**
 * The kind of every field that may leave the machine (docs/plans/phase-6.md §12.3). {@code ChatPayloadGuard} checks
 * each value against its kind; a field without a kind fails the startup check, so nothing unclassified is ever sent.
 */
public enum OutboundKind {
    /** Money as text with two decimals, {@code -?\d{1,10}\.\d{2}}. */
    MONEY,
    /** A percent as text with one decimal. */
    PERCENT,
    /** A multiple as text with two decimals ("13.55 times the median"). */
    RATIO,
    /** A number in an alert's quantity (money, percent, count, days or ratio, as its unit says). */
    NUMBER,
    /** A whole number. */
    COUNT,
    /** An ISO date. */
    DATE,
    /** A month, {@code yyyy-MM}. */
    MONTH,
    /** A category name (public, seeded or user-chosen labels). */
    CATEGORY,
    /** A merchant's outbound text: redacted and guard-checked, or {@code MERCHANT WITHHELD}. */
    MERCHANT,
    /** An account alias of this turn ({@code CHECKING-1}), never the account itself. */
    ALIAS,
    /** An enum constant's name. */
    ENUM,
    /** A per-turn reference ({@code t1}, {@code c1}, {@code a1}, {@code s1}) issued in this turn. */
    REF,
    /** An alert setting's property name ({@code min-increase-percent}). */
    SETTING,
    /** True or false. */
    FLAG,
    /** Free text: only the outbound question, the recent answers and the summary. */
    TEXT
}

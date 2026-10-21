package com.finalysis.chat;

/** What a row of the usage ledger ({@code chat_usage}, V22) paid for: the daily cap sums all three (§8). */
public enum UsageKind {
    /** The rounds of one chat turn. */
    TURN,
    /** Folding older turns of a conversation into its summary (CHT-06, §7.2). */
    CONVERSATION_SUMMARY,
    /** A monthly summary written by the model (ANA-05, §11.1); the template costs nothing and is not recorded. */
    MONTHLY_SUMMARY
}

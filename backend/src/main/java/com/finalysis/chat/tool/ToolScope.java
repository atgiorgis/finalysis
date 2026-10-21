package com.finalysis.chat.tool;

import java.util.List;
import java.util.Optional;

/**
 * What a turn has shown the model, for resolving its arguments (docs/plans/phase-6.md §3.1, §4.3): aliases, category
 * names and merchants' outbound texts, mapped back to what the services take. Names the turn didn't issue resolve to
 * nothing, so an invented one is an error, never a zero.
 */
public interface ToolScope {

    Optional<Long> accountId(String alias);

    Optional<Long> categoryId(String name);

    /** Whether a text is the placeholder sent instead of a withheld merchant. */
    boolean isWithheldMerchant(String text);

    /** The stored merchants this turn sent as exactly this text; empty if it sent none. */
    List<String> storedMerchants(String outboundText);
}

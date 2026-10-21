package com.finalysis.chat.tool;

import java.util.List;
import java.util.Optional;

/**
 * What a turn has shown the model, for resolving its arguments (docs/plans/phase-6.md §3.1, §4.3): aliases, category
 * names and merchants' outbound texts, mapped back to what the services take, and the search text prepared for the
 * search. Names that resolve to nothing are an error, never a zero.
 */
public interface ToolScope {

    Optional<Long> accountId(String alias);

    Optional<Long> categoryId(String name);

    /** Whether a text is the placeholder sent instead of a withheld merchant. */
    boolean isWithheldMerchant(String text);

    /**
     * The stored merchants that go out as exactly this text, whether or not the turn has shown it yet (§4.3); empty when
     * no merchant of the data does.
     */
    List<String> storedMerchants(String outboundText);

    /**
     * A search text prepared for {@code search_transactions} (§4.3): redacted like the question, checked by the
     * merchant rules, and embedded when a model is configured. Empty when it may not leave the machine.
     */
    Optional<SearchText> searchText(String typed);
}

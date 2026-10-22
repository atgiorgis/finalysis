package com.finalysis.chat.tool.view;

import static com.finalysis.chat.tool.view.OutboundKind.*;

import java.util.List;

/**
 * {@code search_transactions} (§4.3, step 8b): the categories the search matched, then candidate merchants, name matches
 * first, each with what it spent over the range (a positive amount = money out after refunds) by its ref; and how much
 * of the data search by meaning covers. The search text itself is never sent back.
 *
 * @param byMeaning        false when the search used names only (no embedding model, or its call failed)
 * @param merchants        distinct merchants in the data
 * @param indexedMerchants of those, the ones search by meaning can find
 */
public record SearchView(@Outbound(MONTH) String from, @Outbound(MONTH) String to, @Outbound(FLAG) Boolean byMeaning,
                         List<CategoryMatch> categories, List<Candidate> candidates,
                         @Outbound(COUNT) Integer merchants, @Outbound(COUNT) Integer indexedMerchants) {

    /** A category the search matched: by its name ({@code NAME}) or by meaning ({@code MEANING}, with similarity). */
    public record CategoryMatch(@Outbound(CATEGORY) String category, @Outbound(ENUM) String match,
                                @Outbound(SIMILARITY) String similarity) {
    }

    /**
     * @param match      {@code NAME}, {@code MEANING}, or {@code CATEGORY} (a merchant of a matched category)
     * @param similarity cosine similarity, two decimals (a {@code CATEGORY} candidate's is its category's); null when
     *                   found by a name. Not a figure for the answer
     */
    public record Candidate(@Outbound(REF) String ref, @Outbound(MERCHANT) String merchant,
                            @Outbound(ENUM) String match, @Outbound(SIMILARITY) String similarity,
                            @Outbound(CATEGORY) String category, @Outbound(MONEY) String spent,
                            @Outbound(COUNT) Integer count) {
    }
}

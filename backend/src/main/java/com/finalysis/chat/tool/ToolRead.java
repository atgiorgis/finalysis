package com.finalysis.chat.tool;

import com.finalysis.alert.AlertListResponse;
import com.finalysis.analytics.AccountTotalsResponse;
import com.finalysis.analytics.CategoryTotalsResponse;
import com.finalysis.analytics.ComparisonResponse;
import com.finalysis.analytics.CoverageResponse;
import com.finalysis.analytics.DrillDownFilter;
import com.finalysis.analytics.DrillDownResponse;
import com.finalysis.analytics.MerchantTotalsResponse;
import com.finalysis.analytics.MonthRange;
import com.finalysis.analytics.MonthlyTotalsResponse;
import com.finalysis.analytics.RecurringChargesResponse;
import com.finalysis.analytics.TrendsResponse;
import java.math.BigDecimal;
import java.util.List;

/**
 * What a tool read: the Phase 5 service's own record, unchanged (for the two search tools, the drill-down totals of each
 * merchant group they read). It never leaves the machine as is: the executor hands
 * it to the outbound projection, one view per kind (docs/plans/phase-6.md §12.1), and the projection's switch over this
 * sealed type makes a tool without a view a compile error.
 */
public sealed interface ToolRead {

    record MonthTotals(MonthlyTotalsResponse response) implements ToolRead {
    }

    record CategoryTotals(CategoryTotalsResponse response) implements ToolRead {
    }

    record AccountTotals(AccountTotalsResponse response) implements ToolRead {
    }

    record TopMerchants(MerchantTotalsResponse response) implements ToolRead {
    }

    record Trends(TrendsResponse response) implements ToolRead {
    }

    record Comparison(ComparisonResponse response) implements ToolRead {
    }

    /** @param limit  how many rows the model asked for; the page holds as many at most
     * @param filter the drill-down it ran, for the total's citation
     */
    record Transactions(DrillDownResponse response, int limit, DrillDownFilter filter) implements ToolRead {

        /** The account it was filtered to, or null. */
        public Long accountId() {
            return filter.accountId().orElse(null);
        }
    }

    record Recurring(RecurringChargesResponse response) implements ToolRead {
    }

    record Alerts(AlertListResponse response) implements ToolRead {
    }

    record Coverage(CoverageResponse response) implements ToolRead {
    }

    /**
     * {@code search_transactions} (§4.3): the candidates, name matches first, then by meaning, each with its spending
     * over the range; and the index's coverage.
     *
     * @param range        the months the spending covers; null without candidates or data
     * @param byMeaning    whether the search used a vector (false: by name only)
     */
    record Search(MonthRange range, List<CategoryMatch> categories, List<Candidate> candidates,
                  MerchantSearchQuery.Coverage coverage, boolean byMeaning) implements ToolRead {

        public Search {
            categories = List.copyOf(categories);
            candidates = List.copyOf(candidates);
        }

        public Search(MonthRange range, List<Candidate> candidates, MerchantSearchQuery.Coverage coverage,
                      boolean byMeaning) {
            this(range, List.of(), candidates, coverage, byMeaning);
        }

        /**
         * How a candidate was found: its own name ({@code NAME}) or document ({@code MEANING}), or as a merchant of a
         * category the search matched ({@code CATEGORY}, step 8b).
         */
        public enum Match { NAME, MEANING, CATEGORY }

        /** A category the search matched, by its name ({@code NAME}, similarity null) or by meaning. */
        public record CategoryMatch(String category, Match match, BigDecimal similarity) {
        }

        /**
         * @param similarity cosine similarity to two decimals: the merchant's, or for {@code CATEGORY} its category's;
         *                   null when found by a name
         */
        public record Candidate(Match match, BigDecimal similarity, MerchantSpend spend) {
        }
    }

    /**
     * {@code get_merchant_totals} (§3.2): each merchant group's spending over the range, and their sum, in Java.
     *
     * @param range the months covered; null when there is no data
     */
    record MerchantTotals(MonthRange range, Long accountId, List<MerchantSpend> merchants, BigDecimal combinedSpent,
                          int combinedCount) implements ToolRead {

        public MerchantTotals {
            merchants = List.copyOf(merchants);
        }
    }
}

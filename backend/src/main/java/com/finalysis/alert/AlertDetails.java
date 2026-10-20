package com.finalysis.alert;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.finalysis.analytics.Average;
import com.finalysis.analytics.CategoryRef;
import com.finalysis.api.ApiMoney;
import com.finalysis.api.ApiPercent;
import com.finalysis.api.ApiRatio;
import java.math.BigDecimal;
import java.util.List;

/**
 * What each check says about its alert (docs/plans/phase-5.md §6.3), beside the common {@link AlertBasis}. Sealed,
 * with the check written as {@code type}, the {@code ReviewItem} idiom. Amounts are magnitudes (§4): positive = money
 * out. Step 6 adds the two recurring checks' records.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(AlertDetails.CategorySpike.class),
        @JsonSubTypes.Type(AlertDetails.LargeTransaction.class),
        @JsonSubTypes.Type(AlertDetails.PossibleDuplicate.class)})
public sealed interface AlertDetails {

    /**
     * @param spent           the category's net spending in the month, after refunds in it
     * @param baseline        the trailing 3-month average over complete months (§7)
     * @param increaseAmount  {@code spent − baseline}
     * @param increasePercent {@code increaseAmount / baseline × 100}, one decimal
     */
    @JsonTypeName("CATEGORY_SPIKE")
    record CategorySpike(CategoryRef category, @ApiMoney BigDecimal spent, Average baseline,
                         @ApiMoney BigDecimal increaseAmount, @ApiPercent BigDecimal increasePercent)
            implements AlertDetails {
    }

    /**
     * @param category     the txn's category; null when it is uncategorized (its history is then the uncategorized
     *                     money out)
     * @param median       the median charge of that history ({@code percentile_disc(0.5)}, an actual charge)
     * @param historyCount how many charges the median is over
     * @param timesMedian  the charge over the median, two decimals
     */
    @JsonTypeName("LARGE_TRANSACTION")
    record LargeTransaction(TxnRef txn, CategoryRef category, @ApiMoney BigDecimal median, int historyCount,
                            @ApiRatio BigDecimal timesMedian) implements AlertDetails {
    }

    /**
     * @param reasons  why the txns were grouped, sorted; both when some pairs match one way and some the other
     * @param amount   the charge, when every txn of the group has the same one; null otherwise (only possible for
     *                 {@code SAME_EXTERNAL_ID})
     * @param merchant the cleaned merchant, when every txn has the same one; null otherwise
     */
    @JsonTypeName("POSSIBLE_DUPLICATE")
    record PossibleDuplicate(List<DuplicateReason> reasons, @ApiMoney BigDecimal amount, String merchant)
            implements AlertDetails {
    }

    /** Why two txns look like one charge twice. */
    enum DuplicateReason {

        /** Same account, amount and merchant (or raw description when there is no merchant), within the window. */
        SAME_MERCHANT_AMOUNT_DATE,

        /** Same account and OFX FITID ({@code txn.external_id}): the safety net ING-03 promised. */
        SAME_EXTERNAL_ID
    }
}

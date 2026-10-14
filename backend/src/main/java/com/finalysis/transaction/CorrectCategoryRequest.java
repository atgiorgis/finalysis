package com.finalysis.transaction;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PUT /api/transactions/{id}/category}.
 *
 * @param applyToMerchant also upsert a correction rule for the txn's merchant and direction, so its other
 *                        txns follow (CAT-04); default true
 */
public record CorrectCategoryRequest(@NotNull Long categoryId, Boolean applyToMerchant) {

    boolean appliesToMerchant() {
        return applyToMerchant == null || applyToMerchant;
    }
}

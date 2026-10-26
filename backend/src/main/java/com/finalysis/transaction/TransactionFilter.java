package com.finalysis.transaction;

import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import com.finalysis.categorize.CategorySource;
import com.finalysis.categorize.MerchantCleaner;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The filters and page of {@code GET /api/transactions}; null = no filter. Filters combine with AND.
 *
 * @param statementId   only the rows this statement owns (step 7d, the Import page's "open in Transactions")
 * @param uncategorized true: only txns without a category; false: only txns with one
 * @param from          first {@code txn_date}, inclusive
 * @param to            last {@code txn_date}, inclusive
 * @param merchant      the cleaned merchant, exactly; normalized as merchants are (case, accents, spaces)
 * @param page          zero-based
 * @param size          1 to {@link #MAX_SIZE}
 */
public record TransactionFilter(Long accountId, Long statementId, Long categoryId, Boolean uncategorized, CategorySource source,
                                Boolean isTransfer, LocalDate from, LocalDate to, String merchant, int page,
                                int size) {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    /**
     * Checks the combination and normalizes the merchant.
     *
     * @throws InvalidRequestException naming every invalid parameter
     */
    public TransactionFilter {
        List<InvalidField> errors = new ArrayList<>();
        if (page < 0) {
            errors.add(new InvalidField("page", "must be 0 or more"));
        }
        if (size < 1 || size > MAX_SIZE) {
            errors.add(new InvalidField("size", "must be 1 to " + MAX_SIZE));
        }
        if (from != null && to != null && from.isAfter(to)) {
            errors.add(new InvalidField("to", "must not be before from"));
        }
        if (Boolean.TRUE.equals(uncategorized) && categoryId != null) {
            errors.add(new InvalidField("uncategorized", "can't be combined with categoryId"));
        }
        if (Boolean.TRUE.equals(uncategorized) && source != null) {
            errors.add(new InvalidField("uncategorized", "can't be combined with source"));
        }
        if (merchant != null) {
            merchant = MerchantCleaner.normalize(merchant);
            if (merchant.isEmpty()) {
                errors.add(new InvalidField("merchant", "must not be blank"));
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
    }
}

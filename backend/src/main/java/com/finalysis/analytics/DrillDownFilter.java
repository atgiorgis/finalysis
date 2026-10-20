package com.finalysis.analytics;

import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What {@code GET /api/analytics/transactions} lists: the txns behind one total (a cell of the months, categories,
 * accounts or merchants breakdowns). Every filter is optional and they combine with AND.
 *
 * @param month      one month; not with {@code from} or {@code to}
 * @param from       first month, as in the other endpoints
 * @param to         last month, as in the other endpoints
 * @param bucket     only txns in this bucket ({@link TxnBucketSql#BUCKET})
 * @param measure    {@code SPENT}: only spending rows (expense and refund buckets, uncategorized money out);
 *                   {@code RECEIVED}: only income rows (income bucket, uncategorized money in)
 * @param categoryId only txns with this category; not with {@code bucket=UNCATEGORIZED}
 * @param merchant   only txns with exactly this cleaned merchant, as the merchants endpoint returns it
 * @param noMerchant true: only txns without a merchant (the merchants endpoint's null row); not with
 *                   {@code merchant}
 * @param page       zero-based
 * @param size       1 to {@value #MAX_SIZE}
 */
public record DrillDownFilter(Optional<YearMonth> month, Optional<YearMonth> from, Optional<YearMonth> to,
                              Optional<TxnBucket> bucket, Optional<TrendMeasure> measure, Optional<Long> accountId,
                              Optional<Long> categoryId, Optional<String> merchant, boolean noMerchant, int page,
                              int size) {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    /** @throws InvalidRequestException naming every invalid parameter */
    public DrillDownFilter {
        List<InvalidField> errors = new ArrayList<>();
        if (month.isPresent() && (from.isPresent() || to.isPresent())) {
            errors.add(new InvalidField("month", "can't be combined with from or to"));
        }
        if (categoryId.isPresent() && bucket.filter(b -> b == TxnBucket.UNCATEGORIZED).isPresent()) {
            errors.add(new InvalidField("categoryId", "can't be combined with bucket UNCATEGORIZED"));
        }
        if (merchant.isPresent() && merchant.get().isBlank()) {
            errors.add(new InvalidField("merchant", "must not be blank"));
        }
        if (merchant.isPresent() && noMerchant) {
            errors.add(new InvalidField("noMerchant", "can't be combined with merchant"));
        }
        if (page < 0) {
            errors.add(new InvalidField("page", "must be 0 or more"));
        }
        if (size < 1 || size > MAX_SIZE) {
            errors.add(new InvalidField("size", "must be 1 to " + MAX_SIZE));
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
    }
}

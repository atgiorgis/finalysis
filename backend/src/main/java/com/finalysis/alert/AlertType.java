package com.finalysis.alert;

/**
 * The checks (ANA-04, docs/plans/phase-5.md §6.1, §6.3), each with the slug that starts its alert keys. The values
 * match V20's {@code ck_alert_dismissal_type}. Step 5 builds the first three; step 6 the recurring two.
 */
public enum AlertType {

    /** An expense category's spending in a month well above its trailing 3-month average. */
    CATEGORY_SPIKE("category-spike"),

    /** A charge far above its category's recent median. */
    LARGE_TRANSACTION("large-transaction"),

    /** Charges in one account that look like the same one twice. */
    POSSIBLE_DUPLICATE("possible-duplicate"),

    /** A fixed-price recurring charge whose price went up (step 6). */
    RECURRING_PRICE_INCREASE("recurring-price-increase"),

    /** A recurring charge that started while the data was being watched (step 6). */
    NEW_RECURRING("new-recurring");

    private final String slug;

    AlertType(String slug) {
        this.slug = slug;
    }

    /** The key's first part, e.g. {@code category-spike}. */
    public String slug() {
        return slug;
    }
}

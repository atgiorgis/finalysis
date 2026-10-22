package com.finalysis.ai;

/**
 * The documents the chat search index embeds (CHT-02; docs/plans/phase-6.md step 8b), in one place: categorization's
 * refresh writes them, and tests place neighbours by them. Both parts have already been checked to leave the machine:
 * the merchant is its redacted, guard-checked outbound text, the category a category name (public, SEC-01).
 */
public final class SearchDocuments {

    /** What a search document stands for; the {@code kind} column of V23's {@code search_embedding}. */
    public enum Kind { MERCHANT, CATEGORY }

    private SearchDocuments() {
    }

    /** A merchant with its category: {@code SHELL OIL (Transportation)}; the bare text without a category. */
    public static String merchant(String outboundText, String category) {
        return category == null || category.isBlank() ? outboundText : outboundText + " (" + category + ")";
    }

    /** A category: its name. */
    public static String category(String name) {
        return name;
    }
}

package com.finalysis.transaction;

import java.util.List;

/**
 * One page of {@code GET /api/transactions}.
 *
 * @param page  zero-based
 * @param total txns matching the filters, on every page
 */
public record TransactionPage(List<TransactionResponse> items, int page, int size, long total) {
}

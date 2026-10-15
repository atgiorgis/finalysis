package com.finalysis.review;

import java.util.List;

/**
 * {@code GET /api/review-queue} (ING-07, docs/plans/phase-3c.md §4.1).
 *
 * @param counts items of each kind after the {@code accountId} filter, whatever {@code kind} asks for,
 *               so a badge can show every kind while one is listed
 * @param items  newest {@code since} first
 */
public record ReviewQueueResponse(Counts counts, List<ReviewItem> items) {

    public record Counts(int statements, int unparsedUploads, int transferCandidates, int competingCandidates,
                         int possibleMatches, int suggestedOneSidedTransfers, int aiCategoryReviews) {
    }
}

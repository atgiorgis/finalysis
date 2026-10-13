package com.finalysis.transfer;

/**
 * What one detection run did: counts only, so it is safe to log (SEC-10). Body of
 * {@code POST /api/transfers/detect}.
 *
 * @param linksCreated       new MATCHED, AUTO links
 * @param unmatchedCompleted UNMATCHED links completed with their counterpart (§2.4)
 * @param groupsForReview    candidate groups left for the user (§2.3 rule 3)
 */
public record DetectionResult(int linksCreated, int unmatchedCompleted, int groupsForReview) {
}

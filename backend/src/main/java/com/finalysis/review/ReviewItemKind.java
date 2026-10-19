package com.finalysis.review;

/** The kinds of review-queue item (ING-07, docs/plans/phase-3c.md §4.1), in the order {@code counts} lists them. */
public enum ReviewItemKind {
    STATEMENT,
    UNPARSED_UPLOAD,
    TRANSFER_CANDIDATES,
    COMPETING_CANDIDATE,
    POSSIBLE_MATCH,
    SUGGESTED_ONE_SIDED_TRANSFER,
    AI_CATEGORY_REVIEW,
    NOT_TRANSFER_UNCATEGORIZED;

    static ReviewItemKind of(ReviewItem item) {
        return switch (item) {
            case ReviewItem.StatementItem ignored -> STATEMENT;
            case ReviewItem.UnparsedUploadItem ignored -> UNPARSED_UPLOAD;
            case ReviewItem.TransferCandidatesItem ignored -> TRANSFER_CANDIDATES;
            case ReviewItem.CompetingCandidateItem ignored -> COMPETING_CANDIDATE;
            case ReviewItem.PossibleMatchItem ignored -> POSSIBLE_MATCH;
            case ReviewItem.SuggestedOneSidedTransferItem ignored -> SUGGESTED_ONE_SIDED_TRANSFER;
            case ReviewItem.AiCategoryReviewItem ignored -> AI_CATEGORY_REVIEW;
            case ReviewItem.NotTransferUncategorizedItem ignored -> NOT_TRANSFER_UNCATEGORIZED;
        };
    }
}

package com.finalysis.review;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.finalysis.ingestion.BalanceCheck;
import com.finalysis.ingestion.UnparsedReason;
import com.finalysis.transfer.TxnSummary;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One thing waiting for the user (ING-07, docs/plans/phase-3c.md §4.1). Sealed, with the kind written as
 * {@code kind}; Phase 4 added {@code SUGGESTED_ONE_SIDED_TRANSFER} (CAT-08) and
 * {@code AI_CATEGORY_REVIEW} (CAT-02) as more records. Transfer items carry {@link TxnSummary}s, descriptions included: the user needs them to
 * decide (§3).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(ReviewItem.StatementItem.class),
        @JsonSubTypes.Type(ReviewItem.UnparsedUploadItem.class),
        @JsonSubTypes.Type(ReviewItem.TransferCandidatesItem.class),
        @JsonSubTypes.Type(ReviewItem.CompetingCandidateItem.class),
        @JsonSubTypes.Type(ReviewItem.PossibleMatchItem.class),
        @JsonSubTypes.Type(ReviewItem.SuggestedOneSidedTransferItem.class),
        @JsonSubTypes.Type(ReviewItem.AiCategoryReviewItem.class)})
public sealed interface ReviewItem {

    /** When the item appeared; the queue lists the newest first. */
    OffsetDateTime since();

    /** Every account the item touches, for the {@code accountId} filter. */
    @JsonIgnore
    Set<Long> accountIds();

    /** A NEEDS_REVIEW statement the user hasn't reviewed; {@code since} is when it was imported. */
    @JsonTypeName("STATEMENT")
    record StatementItem(long statementId, long accountId, String accountName, String fileName, LocalDate periodStart,
                         LocalDate periodEnd, BalanceCheck balanceCheck, List<String> warnings,
                         OffsetDateTime since) implements ReviewItem {

        @Override
        public Set<Long> accountIds() {
            return Set.of(accountId);
        }
    }

    /** An open unparsed upload (ING-13); {@code since} is when it was uploaded. */
    @JsonTypeName("UNPARSED_UPLOAD")
    record UnparsedUploadItem(long uploadId, long accountId, String accountName, String fileName,
                              UnparsedReason reason, OffsetDateTime since) implements ReviewItem {

        @Override
        public Set<Long> accountIds() {
            return Set.of(accountId);
        }
    }

    /**
     * A candidate group detection left for the user (§2.3 rule 3).
     *
     * @param key   {@code txn-<smallest txn id>}
     * @param txns  ascending by id
     * @param pairs ascending by out txn id, then in txn id
     * @param since the newest of its txns' {@code created_at}
     */
    @JsonTypeName("TRANSFER_CANDIDATES")
    record TransferCandidatesItem(String key, List<TxnSummary> txns, List<PairSummary> pairs,
                                  OffsetDateTime since) implements ReviewItem {

        @Override
        public Set<Long> accountIds() {
            return txns.stream().map(TxnSummary::accountId).collect(Collectors.toUnmodifiableSet());
        }
    }

    /**
     * An unconfirmed AUTO link that a later txn competes for (§4.6).
     *
     * @param key         {@code link-<linkId>}
     * @param competitors the txns outside the link, ascending by id
     * @param pairs       the link's own pair and every competing pair, ascending by out txn id, then in txn id
     * @param since       the newest of the competitors' {@code created_at}
     */
    @JsonTypeName("COMPETING_CANDIDATE")
    record CompetingCandidateItem(String key, long linkId, TxnSummary out, TxnSummary in,
                                  List<TxnSummary> competitors, List<PairSummary> pairs,
                                  OffsetDateTime since) implements ReviewItem {

        @Override
        public Set<Long> accountIds() {
            return Stream.concat(Stream.of(out, in), competitors.stream())
                    .map(TxnSummary::accountId).collect(Collectors.toUnmodifiableSet());
        }
    }

    /**
     * Two one-sided links that could be joined (§4.5).
     *
     * @param key   {@code links-<outLinkId>-<inLinkId>}
     * @param since the newer link's {@code created_at}
     */
    @JsonTypeName("POSSIBLE_MATCH")
    record PossibleMatchItem(String key, long outLinkId, long inLinkId, TxnSummary out, TxnSummary in,
                             long daysApart, OffsetDateTime since) implements ReviewItem {

        @Override
        public Set<Long> accountIds() {
            return Set.of(out.accountId(), in.accountId());
        }
    }

    /**
     * A txn in a Transfer-kind category with no transfer link (CAT-08, docs/plans/phase-4.md §8). The user
     * marks it one-sided, links it by hand, marks it not a transfer, or corrects its category.
     *
     * @param key            {@code txn-<id>}
     * @param categorySource {@code RULE}, {@code AI} or {@code USER}
     * @param ruleId         the rule behind a {@code RULE} category, else null
     * @param since          when the txn was categorized
     */
    @JsonTypeName("SUGGESTED_ONE_SIDED_TRANSFER")
    record SuggestedOneSidedTransferItem(String key, TxnSummary txn, String categorySource, Long ruleId,
                                         OffsetDateTime since) implements ReviewItem {

        @Override
        public Set<Long> accountIds() {
            return Set.of(txn.accountId());
        }
    }

    /**
     * A txn the AI looked at but left uncategorized (CAT-02, docs/plans/phase-4.md step 7 notes). The user gives it
     * a category with a correction ({@code PUT /api/transactions/{id}/category}), which removes the item.
     *
     * @param key    {@code txn-<id>}
     * @param reason {@code LOW_CONFIDENCE} (the answer was below the threshold) or {@code INVALID_ANSWER} (two
     *               invalid answers)
     * @param since  when the AI left it
     */
    @JsonTypeName("AI_CATEGORY_REVIEW")
    record AiCategoryReviewItem(String key, TxnSummary txn, String reason, OffsetDateTime since)
            implements ReviewItem {

        @Override
        public Set<Long> accountIds() {
            return Set.of(txn.accountId());
        }
    }
}

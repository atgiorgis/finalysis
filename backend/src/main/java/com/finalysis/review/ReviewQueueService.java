package com.finalysis.review;

import com.finalysis.ingestion.Statement;
import com.finalysis.ingestion.StatementRepository;
import com.finalysis.ingestion.Txn;
import com.finalysis.ingestion.TxnRepository;
import com.finalysis.ingestion.UnparsedUpload;
import com.finalysis.ingestion.UnparsedUploadRepository;
import com.finalysis.review.AiCategoryReviewQuery.Waiting;
import com.finalysis.review.SuggestedTransferQuery.Suggested;
import com.finalysis.transfer.CandidateGroup;
import com.finalysis.transfer.CandidatePair;
import com.finalysis.transfer.CompetingCandidateQuery;
import com.finalysis.transfer.CompetingCandidateQuery.CompetingPair;
import com.finalysis.transfer.PossibleMatchQuery;
import com.finalysis.transfer.PossibleMatchQuery.PossibleMatch;
import com.finalysis.transfer.TransferCandidateQuery;
import com.finalysis.transfer.TransferLink;
import com.finalysis.transfer.TransferLinkRepository;
import com.finalysis.transfer.TransferProperties;
import com.finalysis.transfer.TransferResolver;
import com.finalysis.transfer.TxnSummary;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the review queue (ING-07, docs/plans/phase-3c.md §4.1) at request time, so it can't go stale:
 * statements and unparsed uploads from their tables, the transfer items from the same read-only
 * queries and resolver as detection (§2.3, §4.5, §4.6), suggested one-sided transfers from their categories
 * (CAT-08, phase-4.md §8), txns the AI left uncategorized (CAT-02), and uncategorized txns marked not a transfer
 * (step 9d). Nothing is stored and nothing is written.
 *
 * <p>One REPEATABLE READ transaction: every query sees the same snapshot, so a detection run committing
 * between two of them can't make an item show a link it no longer has.
 */
@Service
public class ReviewQueueService {

    private static final Comparator<CandidatePair> PAIR_ORDER = Comparator
            .comparingLong((CandidatePair pair) -> pair.out().txnId())
            .thenComparingLong(pair -> pair.in().txnId());

    /** Newest first; ties by kind, then by the item's own id, so the order is stable. */
    private static final Comparator<ReviewItem> NEWEST_FIRST = Comparator
            .comparing(ReviewItem::since, Comparator.reverseOrder())
            .thenComparing(ReviewItemKind::of)
            .thenComparingLong(item -> tieBreak(item)[0])
            .thenComparingLong(item -> tieBreak(item)[1]);

    private final StatementRepository statements;
    private final UnparsedUploadRepository uploads;
    private final TxnRepository txns;
    private final TransferLinkRepository links;
    private final TransferCandidateQuery candidates;
    private final TransferResolver resolver;
    private final CompetingCandidateQuery competing;
    private final PossibleMatchQuery possibleMatches;
    private final SuggestedTransferQuery suggestedTransfers;
    private final AiCategoryReviewQuery aiCategoryReviews;
    private final NotTransferUncategorizedQuery notTransferUncategorized;
    private final TransferProperties properties;

    public ReviewQueueService(StatementRepository statements, UnparsedUploadRepository uploads, TxnRepository txns,
                              TransferLinkRepository links, TransferCandidateQuery candidates,
                              TransferResolver resolver, CompetingCandidateQuery competing,
                              PossibleMatchQuery possibleMatches, SuggestedTransferQuery suggestedTransfers,
                              AiCategoryReviewQuery aiCategoryReviews,
                              NotTransferUncategorizedQuery notTransferUncategorized, TransferProperties properties) {
        this.statements = statements;
        this.uploads = uploads;
        this.txns = txns;
        this.links = links;
        this.candidates = candidates;
        this.resolver = resolver;
        this.competing = competing;
        this.possibleMatches = possibleMatches;
        this.suggestedTransfers = suggestedTransfers;
        this.aiCategoryReviews = aiCategoryReviews;
        this.notTransferUncategorized = notTransferUncategorized;
        this.properties = properties;
    }

    /**
     * @param kind      only items of this kind (null = all); {@code counts} ignores it
     * @param accountId only items touching this account (null = all)
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReviewQueueResponse list(ReviewItemKind kind, Long accountId) {
        int window = properties.windowDays();
        List<CandidateGroup> groups = resolver.resolve(candidates.find(window)).forReview();
        List<CompetingPair> competingPairs = competing.find(window);
        List<PossibleMatch> matches = possibleMatches.find(window);

        Set<Long> txnIds = new TreeSet<>();
        groups.forEach(group -> txnIds.addAll(group.txnIds()));
        competingPairs.forEach(competingPair -> addIds(txnIds, competingPair.pair()));
        matches.forEach(match -> addIds(txnIds, match.pair()));
        // A txn in a group or competing for a link is listed there, not as a suggestion (§8).
        List<Suggested> suggested = suggestedTransfers.find().stream()
                .filter(suggestion -> !txnIds.contains(suggestion.txnId()))
                .toList();
        suggested.forEach(suggestion -> txnIds.add(suggestion.txnId()));
        // Uncategorized, so never a suggested transfer; a linked txn is never listed (the query leaves it out).
        List<Waiting> waiting = aiCategoryReviews.find();
        waiting.forEach(item -> txnIds.add(item.txnId()));
        // Uncategorized and never linked (an excluded txn is on no link), so in no other item.
        List<NotTransferUncategorizedQuery.Waiting> notTransfer = notTransferUncategorized.find();
        notTransfer.forEach(item -> txnIds.add(item.txnId()));
        Map<Long, Txn> txnById = byId(txnIds, txns::findWithAccountByIdIn, Txn::getId);
        Map<Long, TransferLink> linkById = byId(
                competingPairs.stream().map(CompetingPair::linkId).collect(Collectors.toSet()),
                links::findWithSidesByIdIn, TransferLink::getId);

        List<ReviewItem> all = new ArrayList<>();
        statements.findAwaitingReview().forEach(statement -> all.add(statementItem(statement)));
        uploads.findAllOpenWithAccount().forEach(upload -> all.add(uploadItem(upload)));
        groups.forEach(group -> all.add(candidatesItem(group, txnById)));
        competingByLink(competingPairs).forEach((linkId, pairs) ->
                all.add(competingItem(linkById.get(linkId), pairs, txnById)));
        matches.forEach(match -> all.add(possibleMatchItem(match, txnById)));
        suggested.forEach(suggestion -> all.add(suggestedItem(suggestion, txnById)));
        waiting.forEach(item -> all.add(aiCategoryReviewItem(item, txnById)));
        notTransfer.forEach(item -> all.add(notTransferItem(item, txnById)));

        List<ReviewItem> forAccount = all.stream()
                .filter(item -> accountId == null || item.accountIds().contains(accountId))
                .toList();
        List<ReviewItem> items = forAccount.stream()
                .filter(item -> kind == null || ReviewItemKind.of(item) == kind)
                .sorted(NEWEST_FIRST)
                .toList();
        return new ReviewQueueResponse(counts(forAccount), items);
    }

    private static ReviewItem statementItem(Statement statement) {
        return new ReviewItem.StatementItem(statement.getId(), statement.getAccount().getId(),
                statement.getAccount().getName(), statement.getSourceFileName(), statement.getPeriodStart(),
                statement.getPeriodEnd(), statement.getBalanceCheck(), List.copyOf(statement.getImportWarnings()),
                statement.getImportedAt());
    }

    private static ReviewItem uploadItem(UnparsedUpload upload) {
        return new ReviewItem.UnparsedUploadItem(upload.getId(), upload.getAccount().getId(),
                upload.getAccount().getName(), upload.getFileName(), upload.getReason(), upload.getCreatedAt());
    }

    private static ReviewItem candidatesItem(CandidateGroup group, Map<Long, Txn> txnById) {
        List<Txn> groupTxns = group.txnIds().stream().map(txnById::get).toList();
        return new ReviewItem.TransferCandidatesItem("txn-" + group.txnIds().first(),
                groupTxns.stream().map(TxnSummary::from).toList(),
                group.pairs().stream().map(PairSummary::from).toList(),
                newest(groupTxns));
    }

    /** One item per link: its own pair, then every txn competing for one of its sides. */
    private static ReviewItem competingItem(TransferLink link, List<CandidatePair> competingPairs,
                                            Map<Long, Txn> txnById) {
        TxnSummary out = TxnSummary.from(link.getOutTxn());
        TxnSummary in = TxnSummary.from(link.getInTxn());
        Set<Long> competitorIds = new TreeSet<>();
        for (CandidatePair pair : competingPairs) {
            competitorIds.add(pair.out().txnId() == out.id() ? pair.in().txnId() : pair.out().txnId());
        }
        List<Txn> competitors = competitorIds.stream().map(txnById::get).toList();

        List<PairSummary> pairs = new ArrayList<>();
        pairs.add(new PairSummary(out.id(), in.id(),
                Math.abs(in.effectiveDate().toEpochDay() - out.effectiveDate().toEpochDay())));
        competingPairs.stream().sorted(PAIR_ORDER).map(PairSummary::from).forEach(pairs::add);
        pairs.sort(Comparator.comparingLong(PairSummary::outTxnId).thenComparingLong(PairSummary::inTxnId));

        return new ReviewItem.CompetingCandidateItem("link-" + link.getId(), link.getId(), out, in,
                competitors.stream().map(TxnSummary::from).toList(), List.copyOf(pairs), newest(competitors));
    }

    private static ReviewItem possibleMatchItem(PossibleMatch match, Map<Long, Txn> txnById) {
        return new ReviewItem.PossibleMatchItem("links-" + match.outLinkId() + "-" + match.inLinkId(),
                match.outLinkId(), match.inLinkId(),
                TxnSummary.from(txnById.get(match.pair().out().txnId())),
                TxnSummary.from(txnById.get(match.pair().in().txnId())),
                match.pair().daysApart(), match.since());
    }

    private static ReviewItem suggestedItem(Suggested suggestion, Map<Long, Txn> txnById) {
        return new ReviewItem.SuggestedOneSidedTransferItem("txn-" + suggestion.txnId(),
                TxnSummary.from(txnById.get(suggestion.txnId())), suggestion.categorySource(), suggestion.ruleId(),
                suggestion.since());
    }

    private static ReviewItem aiCategoryReviewItem(Waiting waiting, Map<Long, Txn> txnById) {
        return new ReviewItem.AiCategoryReviewItem("txn-" + waiting.txnId(),
                TxnSummary.from(txnById.get(waiting.txnId())), waiting.reason(), waiting.since());
    }

    private static ReviewItem notTransferItem(NotTransferUncategorizedQuery.Waiting waiting, Map<Long, Txn> txnById) {
        return new ReviewItem.NotTransferUncategorizedItem("txn-" + waiting.txnId(),
                TxnSummary.from(txnById.get(waiting.txnId())), waiting.since());
    }

    private static ReviewQueueResponse.Counts counts(List<ReviewItem> items) {
        Map<ReviewItemKind, Integer> byKind = new EnumMap<>(ReviewItemKind.class);
        items.forEach(item -> byKind.merge(ReviewItemKind.of(item), 1, Integer::sum));
        return new ReviewQueueResponse.Counts(
                byKind.getOrDefault(ReviewItemKind.STATEMENT, 0),
                byKind.getOrDefault(ReviewItemKind.UNPARSED_UPLOAD, 0),
                byKind.getOrDefault(ReviewItemKind.TRANSFER_CANDIDATES, 0),
                byKind.getOrDefault(ReviewItemKind.COMPETING_CANDIDATE, 0),
                byKind.getOrDefault(ReviewItemKind.POSSIBLE_MATCH, 0),
                byKind.getOrDefault(ReviewItemKind.SUGGESTED_ONE_SIDED_TRANSFER, 0),
                byKind.getOrDefault(ReviewItemKind.AI_CATEGORY_REVIEW, 0),
                byKind.getOrDefault(ReviewItemKind.NOT_TRANSFER_UNCATEGORIZED, 0));
    }

    /** The competing pairs of each link, by link id ascending (the query's order). */
    private static Map<Long, List<CandidatePair>> competingByLink(List<CompetingPair> pairs) {
        Map<Long, List<CandidatePair>> byLink = new LinkedHashMap<>();
        pairs.forEach(pair -> byLink.computeIfAbsent(pair.linkId(), id -> new ArrayList<>()).add(pair.pair()));
        return byLink;
    }

    private static OffsetDateTime newest(List<Txn> txns) {
        return txns.stream().map(Txn::getCreatedAt).max(Comparator.naturalOrder()).orElseThrow();
    }

    private static void addIds(Set<Long> ids, CandidatePair pair) {
        ids.add(pair.out().txnId());
        ids.add(pair.in().txnId());
    }

    private static <T> Map<Long, T> byId(Set<Long> ids, Function<Collection<Long>, List<T>> load,
                                         Function<T, Long> id) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return load.apply(ids).stream().collect(Collectors.toMap(id, Function.identity()));
    }

    /** The item's own ids, for items with the same {@code since} and kind. */
    private static long[] tieBreak(ReviewItem item) {
        return switch (item) {
            case ReviewItem.StatementItem statement -> new long[] {statement.statementId(), 0};
            case ReviewItem.UnparsedUploadItem upload -> new long[] {upload.uploadId(), 0};
            case ReviewItem.TransferCandidatesItem group -> new long[] {group.txns().getFirst().id(), 0};
            case ReviewItem.CompetingCandidateItem competing -> new long[] {competing.linkId(), 0};
            case ReviewItem.PossibleMatchItem match -> new long[] {match.outLinkId(), match.inLinkId()};
            case ReviewItem.SuggestedOneSidedTransferItem suggestion -> new long[] {suggestion.txn().id(), 0};
            case ReviewItem.AiCategoryReviewItem waiting -> new long[] {waiting.txn().id(), 0};
            case ReviewItem.NotTransferUncategorizedItem waiting -> new long[] {waiting.txn().id(), 0};
        };
    }
}

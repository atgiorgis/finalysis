package com.finalysis.transfer;

import com.finalysis.api.NotFoundException;
import com.finalysis.ingestion.Txn;
import com.finalysis.ingestion.TxnRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The user's transfer actions (ING-05, docs/plans/phase-3c.md §3): list, confirm, unlink, link by
 * hand, mark one-sided, and mark a txn not a transfer. User actions over the API only; chatbot tools
 * never call these (SEC-03). Every write takes the transfer lock first, so it can't interleave with
 * detection (§2.5).
 *
 * <p>{@code is_transfer} is never written here: the V9 trigger sets it from the links. The CHECK
 * {@code NOT (is_transfer AND transfer_excluded)} fixes the write order. Hibernate flushes inserts
 * before updates and deletes last, so each method flushes between steps: clear an exclusion before
 * inserting a link, and delete a link before setting an exclusion.
 */
@Service
public class TransferService {

    /** Newest first by the later side's effective date, then by id. */
    private static final Comparator<TransferLinkResponse> NEWEST_FIRST = Comparator
            .comparing(TransferService::effectiveDate)
            .thenComparingLong(TransferLinkResponse::id)
            .reversed();

    private final TransferLock lock;
    private final TransferLinkRepository links;
    private final TransferRejectionRepository rejections;
    private final TxnRepository txns;
    private final EntityManager entityManager;

    public TransferService(TransferLock lock, TransferLinkRepository links, TransferRejectionRepository rejections,
                           TxnRepository txns, EntityManager entityManager) {
        this.lock = lock;
        this.links = links;
        this.rejections = rejections;
        this.txns = txns;
        this.entityManager = entityManager;
    }

    /**
     * Every link that passes the filters (null = no filter). Filtered and sorted in Java: a personal
     * account set has a few hundred links at most, and the order needs both sides' dates.
     *
     * @param accountId links with a side on this account
     */
    @Transactional(readOnly = true)
    public List<TransferLinkResponse> list(TransferStatus status, Boolean confirmed, Long accountId) {
        return links.findAllWithSides().stream()
                .filter(link -> status == null || link.getStatus() == status)
                .filter(link -> confirmed == null || confirmed == (link.getConfirmedAt() != null))
                .map(TransferLinkResponse::from)
                .filter(link -> accountId == null || onAccount(link.out(), accountId) || onAccount(link.in(), accountId))
                .sorted(NEWEST_FIRST)
                .toList();
    }

    @Transactional(readOnly = true)
    public TransferLinkResponse get(long id) {
        return TransferLinkResponse.from(link(id));
    }

    /**
     * Links two txns by hand (both sides) or marks one as a one-sided transfer. The explicit action
     * wins over earlier decisions: it clears {@code transfer_excluded} on its txns and, for a pair,
     * deletes the pair's rejection.
     */
    @Transactional
    public TransferLinkResponse create(CreateTransferRequest request) {
        lock.acquire();
        if (request.outTxnId() == null && request.inTxnId() == null) {
            throw new TransferInvalidException("Give outTxnId, inTxnId, or both");
        }
        Txn out = request.outTxnId() == null ? null : txn(request.outTxnId());
        Txn in = request.inTxnId() == null ? null : txn(request.inTxnId());
        checkSigns(out, in);
        if (out != null && in != null) {
            if (out.getAmount().negate().compareTo(in.getAmount()) != 0) {
                throw new TransferInvalidException("The two amounts must be equal and opposite");
            }
            if (out.getAccount().getId().equals(in.getAccount().getId())) {
                throw new TransferInvalidException("The two transactions must be in different accounts");
            }
        }
        Stream.of(out, in).filter(Objects::nonNull).forEach(this::checkNotLinked);

        Stream.of(out, in).filter(Objects::nonNull).forEach(txn -> txn.setTransferExcluded(false));
        if (out != null && in != null) {
            rejections.deleteById(new TransferRejection.Pair(out.getId(), in.getId()));
        }
        entityManager.flush(); // exclusions cleared before the trigger sets is_transfer

        OffsetDateTime now = OffsetDateTime.now();
        TransferLink link = out != null && in != null
                ? TransferLink.linkedByUser(out, in, now)
                : TransferLink.oneSided(out, in, now);
        return TransferLinkResponse.from(links.saveAndFlush(link));
    }

    /** Records that the user looked at the link; idempotent, the first time is kept. */
    @Transactional
    public TransferLinkResponse confirm(long id) {
        lock.acquire();
        TransferLink link = link(id);
        link.confirm(OffsetDateTime.now());
        return TransferLinkResponse.from(links.saveAndFlush(link));
    }

    /** Deletes the link; a MATCHED pair is remembered, so detection never proposes it again. */
    @Transactional
    public void unlink(long id) {
        lock.acquire();
        remove(link(id));
        entityManager.flush();
    }

    /**
     * Marks a txn not a transfer: removes its link, if any (remembering a MATCHED pair), and excludes
     * it from detection.
     */
    @Transactional
    public NotTransferResponse markNotTransfer(long txnId) {
        lock.acquire();
        Txn txn = txn(txnId);
        Long removedLinkId = links.findByTxnId(txnId).map(link -> {
            remove(link);
            return link.getId();
        }).orElse(null);
        entityManager.flush(); // the trigger clears is_transfer before the exclusion is set
        txn.setTransferExcluded(true);
        entityManager.flush();
        return new NotTransferResponse(txnId, true, removedLinkId);
    }

    /** Makes the txn eligible again; the next detection run may link it. */
    @Transactional
    public void undoNotTransfer(long txnId) {
        lock.acquire();
        txn(txnId).setTransferExcluded(false);
        entityManager.flush();
    }

    private void remove(TransferLink link) {
        if (link.getStatus() == TransferStatus.MATCHED) {
            TransferRejection.Pair pair = new TransferRejection.Pair(link.getOutTxn().getId(), link.getInTxn().getId());
            if (!rejections.existsById(pair)) {
                rejections.save(new TransferRejection(pair.outTxnId(), pair.inTxnId()));
            }
        }
        links.delete(link);
    }

    private static void checkSigns(Txn out, Txn in) {
        if (out != null && out.getAmount().signum() >= 0) {
            throw new TransferInvalidException("The out side must be money out (a negative amount)");
        }
        if (in != null && in.getAmount().signum() <= 0) {
            throw new TransferInvalidException("The in side must be money in (a positive amount)");
        }
    }

    private void checkNotLinked(Txn txn) {
        links.findByTxnId(txn.getId()).ifPresent(link -> {
            throw new TransferConflictException(txn.getId(), link.getId());
        });
    }

    private TransferLink link(long id) {
        return links.findById(id).orElseThrow(() -> new NotFoundException("Transfer link", id));
    }

    private Txn txn(long id) {
        return txns.findById(id).orElseThrow(() -> new NotFoundException("Transaction", id));
    }

    private static boolean onAccount(TxnSummary side, long accountId) {
        return side != null && side.accountId() == accountId;
    }

    private static LocalDate effectiveDate(TransferLinkResponse link) {
        if (link.out() == null) {
            return link.in().effectiveDate();
        }
        if (link.in() == null) {
            return link.out().effectiveDate();
        }
        LocalDate out = link.out().effectiveDate();
        LocalDate in = link.in().effectiveDate();
        return out.isAfter(in) ? out : in;
    }
}

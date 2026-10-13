package com.finalysis.transfer;

import com.finalysis.ingestion.Txn;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transfer detection (ING-05, docs/plans/phase-3c.md §2): under the transfer lock, finds the
 * candidate pairs of every account, resolves them, and writes the links. The trigger of V9 sets
 * {@code is_transfer} on both sides.
 *
 * <p>Idempotent: a txn on a link is never a candidate again, except the present side of an
 * UNMATCHED link, so existing links are never changed except by completing an UNMATCHED one. A second
 * run creates nothing. Logs carry counts only, never descriptions or amounts (SEC-10).
 */
@Service
public class TransferDetector {

    private static final Logger log = LoggerFactory.getLogger(TransferDetector.class);

    private final TransferLock lock;
    private final TransferCandidateQuery candidates;
    private final TransferResolver resolver;
    private final TransferLinkRepository links;
    private final EntityManager entityManager;
    private final TransferProperties properties;

    public TransferDetector(TransferLock lock, TransferCandidateQuery candidates, TransferResolver resolver,
                            TransferLinkRepository links, EntityManager entityManager,
                            TransferProperties properties) {
        this.lock = lock;
        this.candidates = candidates;
        this.resolver = resolver;
        this.links = links;
        this.entityManager = entityManager;
        this.properties = properties;
    }

    @Transactional
    public DetectionResult detect() {
        lock.acquire();
        TransferResolver.Resolution resolution = resolver.resolve(candidates.find(properties.windowDays()));

        for (CandidatePair pair : resolution.newLinks()) {
            links.save(TransferLink.detected(txn(pair.out().txnId()), txn(pair.in().txnId())));
        }
        for (TransferResolver.Completion completion : resolution.completions()) {
            // Still UNMATCHED: the query saw it under this lock, and every transfer write takes the lock.
            links.findById(completion.linkId()).orElseThrow()
                    .complete(txn(completion.counterpartTxnId()));
        }
        links.flush();

        DetectionResult result = new DetectionResult(resolution.newLinks().size(),
                resolution.completions().size(), resolution.forReview().size());
        log.info("Transfer detection: {} links created, {} unmatched links completed, {} groups for review",
                result.linksCreated(), result.unmatchedCompleted(), result.groupsForReview());
        return result;
    }

    private Txn txn(long id) {
        return entityManager.getReference(Txn.class, id);
    }
}

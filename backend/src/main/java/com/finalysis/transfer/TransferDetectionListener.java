package com.finalysis.transfer;

import com.finalysis.ingestion.StatementDeleted;
import com.finalysis.ingestion.StatementDeleting;
import com.finalysis.ingestion.StatementImported;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs transfer detection after every successful import and statement delete (docs/plans/phase-3c.md
 * §2.5, §4.4), once the change has committed, so detection sees it and a detection error can't undo it.
 *
 * <p>A statement delete cascades to the links on its rows, which is a transfer write: before any row
 * goes, the delete's own transaction takes the transfer lock here ({@link StatementDeleting}, after
 * the delete took the account lock, so the order is fixed).
 *
 * <p>After commit, the import's transaction is finished but still bound to the thread, so detection
 * gets a transaction of its own ({@code REQUIRES_NEW}); joining the finished one would never commit.
 * A failure is logged with the statement id and the exception type only, never its message, which
 * could quote row values (SEC-10). The import's 201 stands, and the next run catches up, since every
 * run covers every account.
 */
@Component
class TransferDetectionListener {

    private static final Logger log = LoggerFactory.getLogger(TransferDetectionListener.class);

    private final TransferDetector detector;
    private final TransferLock lock;
    private final TransactionTemplate newTransaction;

    TransferDetectionListener(TransferDetector detector, TransferLock lock,
                              PlatformTransactionManager transactionManager) {
        this.detector = detector;
        this.lock = lock;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener // AFTER_COMMIT
    void onImported(StatementImported event) {
        detectAfter("import", event.statementId());
    }

    /** Synchronous, in the delete's transaction: held until it commits or rolls back. */
    @EventListener
    void onDeleting(StatementDeleting event) {
        lock.acquire();
    }

    /** The deleted rows' counterparts can match again. */
    @TransactionalEventListener // AFTER_COMMIT
    void onDeleted(StatementDeleted event) {
        detectAfter("delete", event.statementId());
    }

    /** @param change "import" or "delete" */
    private void detectAfter(String change, long statementId) {
        try {
            newTransaction.executeWithoutResult(status -> detector.detect());
        } catch (RuntimeException e) {
            log.error("Transfer detection after the {} of statement {} failed ({}); the {} stands, and the next "
                    + "detection run catches up", change, statementId, e.getClass().getName(), change);
        }
    }
}

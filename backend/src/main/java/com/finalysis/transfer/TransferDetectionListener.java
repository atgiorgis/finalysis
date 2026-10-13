package com.finalysis.transfer;

import com.finalysis.ingestion.StatementImported;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs transfer detection after every successful import (docs/plans/phase-3c.md §2.5), once the
 * import has committed, so detection sees its rows and a detection error can't undo it.
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
    private final TransactionTemplate newTransaction;

    TransferDetectionListener(TransferDetector detector, PlatformTransactionManager transactionManager) {
        this.detector = detector;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener // AFTER_COMMIT
    void onImported(StatementImported event) {
        try {
            newTransaction.executeWithoutResult(status -> detector.detect());
        } catch (RuntimeException e) {
            log.error("Transfer detection after the import of statement {} failed ({}); the import stands, "
                    + "and the next detection run catches up", event.statementId(), e.getClass().getName());
        }
    }
}

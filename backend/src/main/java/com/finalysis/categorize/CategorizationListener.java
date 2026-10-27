package com.finalysis.categorize;

import com.finalysis.categorize.CategorizationService.RulesResult;
import com.finalysis.categorize.MerchantCleaningService.CleaningResult;
import com.finalysis.ingestion.StatementImported;
import com.finalysis.vault.VaultUnlocked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs the categorization pipeline (docs/plans/phase-4.md §1.2) after every import and each time the database opens
 * (at unlock, or at startup with the vault off; docs/plans/phase-8.md §5.1):
 * merchant cleaning (stage 1), then rules and merchant memory (stages 2–3). After an import it then asks for the AI
 * stage (5) in the background ({@link BackgroundAiCategorization}), which does nothing while AI is off. No AI at
 * startup.
 *
 * <p>After an import commits, it runs after transfer detection ({@link #AFTER_TRANSFER_DETECTION}), since
 * later stages need to know which txns are linked. When the database opens it runs on the opening thread; the
 * categorization lock keeps it apart from imports.
 *
 * <p>A failure never affects the import, which has committed: it is logged with the statement id and the
 * exception type only, never its message, which could quote a description (SEC-10). Each stage is tried on
 * its own, so rules still run on descriptions if cleaning fails. The startup pass catches up on the next
 * start; it runs the rules over every txn, which also applies seed rules added by a migration to txns
 * imported before it.
 */
@Component
class CategorizationListener {

    /** Transfer detection listens to the same event at order 10 ({@code TransferDetectionListener}). */
    static final int AFTER_TRANSFER_DETECTION = 20;

    private static final Logger log = LoggerFactory.getLogger(CategorizationListener.class);

    private final MerchantCleaningService cleaning;
    private final CategorizationService categorization;
    private final BackgroundAiCategorization ai;

    CategorizationListener(MerchantCleaningService cleaning, CategorizationService categorization,
                           BackgroundAiCategorization ai) {
        this.cleaning = cleaning;
        this.categorization = categorization;
        this.ai = ai;
    }

    @Order(AFTER_TRANSFER_DETECTION)
    @TransactionalEventListener // AFTER_COMMIT
    void onImported(StatementImported event) {
        try {
            CleaningResult result = cleaning.cleanStatement(event.statementId());
            log.debug("Merchant cleaning after the import of statement {}: {} rows, {} updated",
                    event.statementId(), result.examined(), result.updated());
        } catch (RuntimeException e) {
            log.error("Merchant cleaning after the import of statement {} failed ({}); the import stands, and the "
                    + "startup backfill catches up", event.statementId(), e.getClass().getName());
        }
        try {
            RulesResult result = categorization.categorizeStatement(event.statementId());
            log.debug("Rules after the import of statement {}: {} rows, {} categorized, {} from merchant memory",
                    event.statementId(), result.examined(), result.ruleCategorized(), result.memoryApplied());
        } catch (RuntimeException e) {
            log.error("Rules after the import of statement {} failed ({}); the import stands, and the next run "
                    + "catches up", event.statementId(), e.getClass().getName());
        }
        try {
            ai.request();
        } catch (RuntimeException e) {
            log.error("AI categorization after the import of statement {} could not start ({}); the next run "
                    + "catches up", event.statementId(), e.getClass().getName());
        }
    }

    @EventListener(VaultUnlocked.class)
    void onReady() {
        try {
            CleaningResult result = cleaning.backfill();
            log.info("Merchant cleaning at startup: {} rows examined, {} updated, full re-clean {}, {} unused "
                    + "embeddings removed", result.examined(), result.updated(), result.fullReclean(),
                    result.orphanEmbeddingsRemoved());
        } catch (RuntimeException e) {
            log.error("Merchant cleaning at startup failed ({}); it runs again at the next start",
                    e.getClass().getName());
        }
        try {
            RulesResult result = categorization.categorizeAll();
            log.info("Rules at startup: {} rows examined, {} categorized, {} cleared, {} from merchant memory",
                    result.examined(), result.ruleCategorized(), result.ruleCleared(), result.memoryApplied());
        } catch (RuntimeException e) {
            log.error("Rules at startup failed ({}); they run again at the next start", e.getClass().getName());
        }
    }
}

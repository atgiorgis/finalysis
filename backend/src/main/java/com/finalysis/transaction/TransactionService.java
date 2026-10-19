package com.finalysis.transaction;

import com.finalysis.api.NotFoundException;
import com.finalysis.categorize.CategorizationLock;
import com.finalysis.categorize.CategorizationService;
import com.finalysis.categorize.CorrectionService;
import com.finalysis.categorize.CorrectionService.Correction;
import com.finalysis.categorize.CorrectionService.Undo;
import com.finalysis.transfer.NotTransferResponse;
import com.finalysis.transfer.TransferLock;
import com.finalysis.transfer.TransferService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transaction API (docs/plans/phase-4.md §9.2): reads here, corrections through {@link CorrectionService},
 * which owns the category columns. Each correction and the txn it returns are one transaction, so the
 * response shows what was committed.
 *
 * <p>"Not a transfer" (ING-05) is here because it touches both features: {@link TransferService} removes the link and
 * sets the exclusion, then {@link CategorizationService} re-runs the rules for the txn, which clear a RULE or AI
 * category of Transfer kind (step 9d notes). The transfer package depends on no categorization class, so this
 * package, which already calls both, joins them in one transaction. It is the only place both advisory locks are
 * held, always transfer first, then categorization, both before any row is written: categorization never takes the
 * transfer lock, and no categorization pass can wait on a row this transaction holds, so the two can't deadlock.
 */
@Service
public class TransactionService {

    private final TransactionQuery query;
    private final CorrectionService corrections;
    private final TransferService transfers;
    private final CategorizationService categorization;
    private final TransferLock transferLock;
    private final CategorizationLock categorizationLock;

    public TransactionService(TransactionQuery query, CorrectionService corrections, TransferService transfers,
                              CategorizationService categorization, TransferLock transferLock,
                              CategorizationLock categorizationLock) {
        this.query = query;
        this.corrections = corrections;
        this.transfers = transfers;
        this.categorization = categorization;
        this.transferLock = transferLock;
        this.categorizationLock = categorizationLock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TransactionPage list(TransactionFilter filter) {
        return query.list(filter);
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(long id) {
        return query.find(id).orElseThrow(() -> new NotFoundException("Transaction", id));
    }

    @Transactional
    public CorrectionResponse correct(long id, CorrectCategoryRequest request) {
        Correction correction = corrections.correct(id, request.categoryId(), request.appliesToMerchant());
        return CorrectionResponse.of(get(id), correction);
    }

    /** Clears a USER category; with {@code removeRule} also deletes the merchant's correction rule (full undo). */
    @Transactional
    public UndoResponse clearCorrection(long id, boolean removeRule) {
        Undo undo = removeRule ? corrections.undo(id) : corrections.clear(id);
        return UndoResponse.of(get(id), undo);
    }

    /**
     * Marks the txn not a transfer, then runs the rules for it: a RULE or AI category of Transfer kind is cleared (a
     * USER category stays), and no Transfer-kind rule matches it again. With no other rule matching, it is listed in
     * the review queue as {@code NOT_TRANSFER_UNCATEGORIZED}.
     */
    @Transactional
    public NotTransferResponse markNotTransfer(long id) {
        transferLock.acquire();
        categorizationLock.acquire();
        NotTransferResponse marked = transfers.markNotTransfer(id);
        categorization.categorizeTxn(id);
        return marked;
    }

    /**
     * Clears "not a transfer", as before step 9d: the next detection run may link the txn, and the next rules pass or
     * AI run may categorize it. Nothing runs now, and a cleared category is not restored.
     */
    @Transactional
    public void undoNotTransfer(long id) {
        transfers.undoNotTransfer(id);
    }
}

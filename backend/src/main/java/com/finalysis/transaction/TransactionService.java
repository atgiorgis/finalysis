package com.finalysis.transaction;

import com.finalysis.api.NotFoundException;
import com.finalysis.categorize.CorrectionService;
import com.finalysis.categorize.CorrectionService.Correction;
import com.finalysis.categorize.CorrectionService.Undo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transaction API (docs/plans/phase-4.md §9.2): reads here, corrections through {@link CorrectionService},
 * which owns the category columns. Each correction and the txn it returns are one transaction, so the
 * response shows what was committed.
 */
@Service
public class TransactionService {

    private final TransactionQuery query;
    private final CorrectionService corrections;

    public TransactionService(TransactionQuery query, CorrectionService corrections) {
        this.query = query;
        this.corrections = corrections;
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

    @Transactional
    public TransactionResponse clearCorrection(long id) {
        corrections.clear(id);
        return get(id);
    }

    @Transactional
    public UndoResponse undoCorrection(long id) {
        Undo undo = corrections.undo(id);
        return UndoResponse.of(get(id), undo);
    }
}

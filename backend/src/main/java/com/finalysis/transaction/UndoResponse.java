package com.finalysis.transaction;

import com.finalysis.categorize.CorrectionService.RuleAction;
import com.finalysis.categorize.CorrectionService.Undo;

/**
 * Body of a full undo ({@code DELETE /api/transactions/{id}/category?removeRule=true}): the txn after the rules
 * ran, the correction rule that was deleted, and how many of the merchant's other txns changed (see {@link Undo}).
 *
 * @param ruleId     null when no rule was deleted
 * @param ruleAction {@code REMOVED} or {@code NONE}
 */
public record UndoResponse(TransactionResponse txn, Long ruleId, RuleAction ruleAction, int otherTxnsUpdated) {

    static UndoResponse of(TransactionResponse txn, Undo undo) {
        return new UndoResponse(txn, undo.ruleId(), undo.ruleAction(), undo.otherTxnsUpdated());
    }
}

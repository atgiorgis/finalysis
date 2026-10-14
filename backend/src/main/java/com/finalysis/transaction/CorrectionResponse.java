package com.finalysis.transaction;

import com.finalysis.categorize.CorrectionService.Correction;
import com.finalysis.categorize.CorrectionService.RuleAction;

/**
 * Body of a correction: the corrected txn, the merchant's correction rule, and what the correction changed on
 * the merchant's other txns in the same direction (see {@link Correction}).
 *
 * @param ruleId null when no rule was touched ({@code ruleAction} {@code NONE})
 */
public record CorrectionResponse(TransactionResponse txn, Long ruleId, RuleAction ruleAction, int otherTxnsUpdated,
                                 int otherTxnsKeptUser, int otherTxnsKeptOtherRule) {

    static CorrectionResponse of(TransactionResponse txn, Correction correction) {
        return new CorrectionResponse(txn, correction.ruleId(), correction.ruleAction(), correction.otherTxnsUpdated(),
                correction.otherTxnsKeptUser(), correction.otherTxnsKeptOtherRule());
    }
}

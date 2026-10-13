package com.finalysis.transfer;

/**
 * A transfer action that breaks a link rule (docs/plans/phase-3c.md §3): same account, wrong signs,
 * unequal amounts, zero, or neither side given. Mapped to 422 {@code transfer-invalid}. The message
 * names the rule and never quotes a description or an amount (SEC-10).
 */
public class TransferInvalidException extends RuntimeException {

    public TransferInvalidException(String rule) {
        super(rule);
    }
}

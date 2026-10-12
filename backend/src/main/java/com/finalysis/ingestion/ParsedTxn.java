package com.finalysis.ingestion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One transaction row as a parser read it, before it is fingerprinted and saved as a {@link Txn}.
 *
 * @param printedDate      the date in the statement's date column (the post date when two dates
 *                         are printed); goes into the fingerprint
 * @param txnDate          when the purchase happened; equals {@code printedDate} unless the format
 *                         carries a separate transaction date
 * @param postDate         the posting date when the format prints one, otherwise null
 * @param rawDescription   exactly as extracted, wrapped lines included; never trimmed, cleaned
 *                         or redacted (DAT-04, CLAUDE.md)
 * @param printedAmount    the amount text as printed, for tracing only
 * @param amount           sign-normalized (negative = money out), scale 2; goes into the fingerprint
 * @param checkNumber      null unless the row is a check
 * @param externalId       the institution's own transaction id (OFX FITID), or null
 * @param sourceLineNumber 1-based line in the file where the row starts (DAT-07)
 * @param sourceLineText   the row's text as it appears in the file (DAT-07)
 */
public record ParsedTxn(LocalDate printedDate, LocalDate txnDate, LocalDate postDate,
                        String rawDescription, String printedAmount, BigDecimal amount,
                        SourceSection section, String checkNumber, String externalId,
                        int sourceLineNumber, String sourceLineText) {

    /** Width of {@code txn.check_number}. */
    static final int CHECK_NUMBER_MAX = 20;

    public ParsedTxn {
        Objects.requireNonNull(printedDate, "printedDate");
        Objects.requireNonNull(txnDate, "txnDate");
        Objects.requireNonNull(rawDescription, "rawDescription");
        Objects.requireNonNull(printedAmount, "printedAmount");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(sourceLineText, "sourceLineText");
        amount = Money.requireNumeric12x2(amount, "amount");
        if (checkNumber != null && checkNumber.length() > CHECK_NUMBER_MAX) {
            throw new IllegalArgumentException("checkNumber is longer than " + CHECK_NUMBER_MAX + " characters");
        }
        if (sourceLineNumber < 1) {
            throw new IllegalArgumentException("sourceLineNumber must be 1 or more");
        }
    }

    /** The raw values that identify this row (ING-03); derived values are deliberately left out. */
    public TransactionFingerprint.Input fingerprintInput(long accountId) {
        return new TransactionFingerprint.Input(accountId, printedDate, amount, rawDescription, section);
    }
}

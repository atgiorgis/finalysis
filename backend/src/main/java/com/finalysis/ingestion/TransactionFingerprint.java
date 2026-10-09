package com.finalysis.ingestion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * SHA-256 fingerprint of one statement row, stored in {@code txn.fingerprint} (DAT-03) and used to
 * skip rows that were already imported when a statement is uploaded again (ING-03).
 *
 * <p><b>Inputs.</b> Only raw, as-printed values go in:
 * <ul>
 *   <li>the account id;</li>
 *   <li>the date printed in the statement's date column ({@code post_date} when the statement
 *       prints two dates), never a date taken from the description;</li>
 *   <li>the printed amount, normalized to exactly two decimal places (sign kept);</li>
 *   <li>the raw description, with wrapped lines joined by a single space, trimmed, uppercased and
 *       whitespace collapsed;</li>
 *   <li>the statement section the row was printed in;</li>
 *   <li>an occurrence number (see below).</li>
 * </ul>
 *
 * <p><b>Why derived values are excluded.</b> Merchant, category, {@code txn_date} taken from an
 * embedded date (e.g. "CHECKCARD 0704 ..."), and counterparty are all outputs of parsing and
 * categorization, and they will improve over time. If any of them were hashed, a better parser
 * would produce different fingerprints for the same printed row, and re-importing an old
 * statement would insert every row a second time. {@link Input} has no field for them, so they
 * cannot be passed in by mistake.
 *
 * <p><b>Occurrence numbers.</b> A statement can legitimately contain two identical rows, such as
 * the same coffee bought twice on one day. Within one statement, the first row with a given set of
 * normalized inputs gets occurrence 1, the next identical row gets 2, and so on
 * ({@link #forStatement}). Re-uploading the statement reproduces the same numbers, so its rows are
 * recognized as duplicates, while both genuine charges are kept.
 *
 * <p><b>Changing the algorithm.</b> Each of the following changes every fingerprint already in
 * the database, so it needs a data migration that recomputes them:
 * <ul>
 *   <li>the normalization rules (amount scale, description cleanup);</li>
 *   <li>the field order or encoding;</li>
 *   <li>a parser's mapping from printed section headings to {@link SourceSection}. A parser
 *       derives the section rather than copying it as printed, so remapping a heading (for
 *       example "Service fees" from {@code OTHER} to {@code FEE}) silently changes the
 *       fingerprints of every row under it.</li>
 * </ul>
 */
public final class TransactionFingerprint {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TransactionFingerprint() {
    }

    /**
     * Raw, as-printed values of one statement row.
     *
     * @param printedDate the date in the statement's date column (post date when two dates are
     *                    printed); never a date taken from the description
     * @param rawDescription the description as extracted; wrapped lines may be separated by
     *                       newlines or spaces
     */
    public record Input(long accountId, LocalDate printedDate, BigDecimal amount,
                        String rawDescription, SourceSection sourceSection) {

        public Input {
            Objects.requireNonNull(printedDate, "printedDate");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(rawDescription, "rawDescription");
            Objects.requireNonNull(sourceSection, "sourceSection");
        }
    }

    /** Normalized inputs; equal keys mean identical rows for occurrence counting. */
    private record Key(long accountId, LocalDate printedDate, String amount, String description,
                       SourceSection sourceSection) {

        static Key of(Input row) {
            return new Key(row.accountId(), row.printedDate(), normalizeAmount(row.amount()),
                    normalizeDescription(row.rawDescription()), row.sourceSection());
        }
    }

    /** Fingerprint of one row with the given occurrence number (1 for the first identical row). */
    public static String of(Input row, int occurrence) {
        return hash(Key.of(row), occurrence);
    }

    /**
     * Fingerprints for all rows of one statement, in input order, with occurrence numbers
     * assigned by counting identical rows (after normalization) from the top.
     */
    public static List<String> forStatement(List<Input> rows) {
        long distinctAccounts = rows.stream().mapToLong(Input::accountId).distinct().count();
        if (distinctAccounts > 1) {
            throw new IllegalArgumentException("Rows of one statement must share one account id");
        }
        Map<Key, Integer> seen = new HashMap<>();
        List<String> fingerprints = new ArrayList<>(rows.size());
        for (Input row : rows) {
            Key key = Key.of(row);
            int occurrence = seen.merge(key, 1, Integer::sum);
            fingerprints.add(hash(key, occurrence));
        }
        return fingerprints;
    }

    static String normalizeAmount(BigDecimal amount) {
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Printed amount has more than two decimal places: " + amount, e);
        }
    }

    static String normalizeDescription(String rawDescription) {
        return WHITESPACE.matcher(rawDescription.strip()).replaceAll(" ").toUpperCase(Locale.ROOT);
    }

    private static String hash(Key key, int occurrence) {
        if (occurrence < 1) {
            throw new IllegalArgumentException("Occurrence must be 1 or more: " + occurrence);
        }
        // Length-prefix every field so no description content can shift a field boundary.
        StringBuilder encoded = new StringBuilder();
        for (String field : List.of(
                Long.toString(key.accountId()),
                key.printedDate().toString(),
                key.amount(),
                key.description(),
                key.sourceSection().name(),
                Integer.toString(occurrence))) {
            encoded.append(field.length()).append(':').append(field);
        }
        return HexFormat.of().formatHex(sha256().digest(encoded.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }
}

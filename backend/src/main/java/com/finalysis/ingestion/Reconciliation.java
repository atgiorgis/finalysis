package com.finalysis.ingestion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Balance checks for one statement (ING-06, ING-10). Plain {@link BigDecimal} arithmetic, always on
 * the rows parsed from the file, duplicates included: the rows an import inserts skip rows already
 * imported, so they can't add up to the statement's balances.
 *
 * <p>Checks, each run when its inputs exist:
 * <ol>
 *   <li><b>Printed balances:</b> opening + rows = closing, when the file prints both.</li>
 *   <li><b>Request balances:</b> openingBalance + rows = closingBalance, when the request sends both.
 *       A request balance that differs from one the file prints fails too (the file's is kept).</li>
 *   <li><b>Balance column:</b> each row's printed running balance = the previous one + the amounts
 *       since. A newest-first file (first date after the last) is checked in reverse. The opening
 *       is derived as the first balance minus the amounts up to it, the closing as the last
 *       balance plus any amounts after it; request balances must agree with them.</li>
 *   <li><b>Continuity</b> (a closing balance without an opening one, as OFX prints LEDGERBAL): the
 *       previous statement's closing + rows = closing. On success the previous closing becomes this
 *       opening. Never derived from the closing alone.</li>
 *   <li><b>Section totals:</b> each printed total = the sum of its section's rows (none = 0).</li>
 * </ol>
 * The result is {@link BalanceCheck#PASSED} when at least one check ran and all passed,
 * {@link BalanceCheck#FAILED} when any failed, else {@link BalanceCheck#UNVERIFIED} with
 * {@link #UNVERIFIED_WARNING}.
 *
 * <p>A failed check adds a warning naming it, with {@code expected} (what the arithmetic gives),
 * {@code actual} (the stated balance or total) and {@code difference} (actual − expected). Amounts
 * in warnings are returned to the user and never logged (SEC-10).
 */
final class Reconciliation {

    static final String UNVERIFIED_WARNING = "balance unverified: nothing to check the rows against "
            + "(send openingBalance and closingBalance, or import the previous statement)";
    static final String FAILED_PREFIX = "reconciliation failed: ";
    /** Failing balance-column rows listed before the rest are only counted. */
    static final int ROWS_LISTED = 10;

    private Reconciliation() {
    }

    /** The statement before this one, ending on the day before it starts or that same day. */
    record Previous(long statementId, BigDecimal closing) {

        Previous {
            Objects.requireNonNull(closing, "closing");
        }
    }

    /** Balances sent with the request, parsed; null when not sent. */
    record RequestBalances(BigDecimal opening, BigDecimal closing) {

        static final RequestBalances NONE = new RequestBalances(null, null);
    }

    /** The balances to store, the outcome, and a warning per failed check (or the unverified one). */
    record Result(BalanceCheck check, BigDecimal opening, BigDecimal closing, List<String> warnings) {

        Result {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * @param previous the adjacent earlier statement with a closing balance, or null when there is
     *                 none
     */
    static Result reconcile(ParsedStatement parsed, RequestBalances request, Previous previous) {
        Checks checks = new Checks();
        BigDecimal rows = total(parsed.txns());
        BigDecimal opening = printedOrRequested(parsed.openingBalance(), request.opening(), "openingBalance", checks);
        BigDecimal closing = printedOrRequested(parsed.closingBalance(), request.closing(), "closingBalance", checks);

        if (parsed.openingBalance() != null && parsed.closingBalance() != null) {
            checks.compare("printed balances: openingBalance + rows = closingBalance",
                    parsed.openingBalance().add(rows), parsed.closingBalance());
        }
        if (request.opening() != null && request.closing() != null) {
            checks.compare("request balances: openingBalance + rows = closingBalance",
                    request.opening().add(rows), request.closing());
        }

        BalanceColumn column = BalanceColumn.of(parsed.txns(), checks);
        if (column != null) {
            if (request.opening() != null) {
                checks.compare("request openingBalance against the balance column", column.opening(), request.opening());
            }
            if (request.closing() != null) {
                checks.compare("request closingBalance against the balance column", column.closing(), request.closing());
            }
            opening = column.opening();
            closing = column.closing();
        } else if (parsed.closingBalance() != null && parsed.openingBalance() == null && previous != null) {
            boolean continuous = checks.compare(continuityCheck(previous),
                    previous.closing().add(rows), parsed.closingBalance());
            if (continuous && request.opening() != null) {
                checks.compare("request openingBalance against statement " + previous.statementId() + "'s closingBalance",
                        previous.closing(), request.opening());
            } else if (continuous) {
                opening = previous.closing();
            }
        }

        checkSectionTotals(parsed, checks);
        return checks.result(opening, closing);
    }

    /**
     * Continuity alone, for a statement imported earlier that was unverified because its previous
     * statement was missing.
     *
     * @param rows the sum of the statement's parsed rows
     */
    static Result continuity(BigDecimal rows, BigDecimal closing, Previous previous) {
        Checks checks = new Checks();
        boolean continuous = checks.compare(continuityCheck(previous), previous.closing().add(rows), closing);
        return checks.result(continuous ? previous.closing() : null, closing);
    }

    static BigDecimal total(List<ParsedTxn> rows) {
        return rows.stream().map(ParsedTxn::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String continuityCheck(Previous previous) {
        return "continuity: statement " + previous.statementId() + "'s closingBalance + rows = closingBalance";
    }

    /** The file's balance, else the request's; both present and different is a failed check. */
    private static BigDecimal printedOrRequested(BigDecimal printed, BigDecimal requested, String field, Checks checks) {
        if (printed == null) {
            return requested;
        }
        if (requested != null) {
            checks.compare("request " + field + " against the file's (the file's is kept)", printed, requested);
        }
        return printed;
    }

    private static void checkSectionTotals(ParsedStatement parsed, Checks checks) {
        // Sorted, so warnings come in the same order every time.
        for (Map.Entry<SourceSection, BigDecimal> entry : new TreeMap<>(parsed.sectionTotals()).entrySet()) {
            SourceSection section = entry.getKey();
            List<ParsedTxn> rows = parsed.txns().stream().filter(row -> row.section() == section).toList();
            checks.compare("section " + section + " total" + (rows.isEmpty() ? " (no " + section + " rows)" : ""),
                    total(rows), entry.getValue());
        }
    }

    /** Balances derived from a running-balance column; null when no row prints one. */
    private record BalanceColumn(BigDecimal opening, BigDecimal closing) {

        static BalanceColumn of(List<ParsedTxn> rows, Checks checks) {
            if (rows.stream().allMatch(row -> row.runningBalance() == null)) {
                return null;
            }
            List<ParsedTxn> ordered = new ArrayList<>(rows);
            if (rows.getFirst().printedDate().isAfter(rows.getLast().printedDate())) {
                ordered = ordered.reversed();
            }
            BigDecimal opening = null;
            BigDecimal last = null;
            BigDecimal since = BigDecimal.ZERO;
            List<String> failures = new ArrayList<>();
            int failed = 0;
            for (ParsedTxn row : ordered) {
                since = since.add(row.amount());
                if (row.runningBalance() == null) {
                    continue; // a blank balance cell: its amount carries over to the next printed balance
                }
                if (last == null) {
                    opening = row.runningBalance().subtract(since);
                } else if (last.add(since).compareTo(row.runningBalance()) != 0) {
                    if (++failed <= ROWS_LISTED) {
                        failures.add(mismatch("balance column, line " + row.sourceLineNumber(), last.add(since),
                                row.runningBalance()));
                    }
                }
                last = row.runningBalance();
                since = BigDecimal.ZERO;
            }
            if (failed > ROWS_LISTED) {
                failures.add(FAILED_PREFIX + "balance column: " + (failed - ROWS_LISTED) + " more rows");
            }
            checks.record(failures);
            return new BalanceColumn(opening, last.add(since));
        }
    }

    private static String mismatch(String check, BigDecimal expected, BigDecimal actual) {
        return FAILED_PREFIX + check + ": expected " + money(expected) + ", actual " + money(actual)
                + ", difference " + money(actual.subtract(expected));
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    /** The checks that ran and the warnings of those that failed. */
    private static final class Checks {

        private int ran;
        private final List<String> warnings = new ArrayList<>();

        /** Runs one check; returns whether it passed. */
        boolean compare(String check, BigDecimal expected, BigDecimal actual) {
            ran++;
            if (expected.compareTo(actual) == 0) {
                return true;
            }
            warnings.add(mismatch(check, expected, actual));
            return false;
        }

        /** Records one check whose failures were worked out by the caller; none = passed. */
        void record(List<String> failures) {
            ran++;
            warnings.addAll(failures);
        }

        Result result(BigDecimal opening, BigDecimal closing) {
            if (ran == 0) {
                return new Result(BalanceCheck.UNVERIFIED, opening, closing, List.of(UNVERIFIED_WARNING));
            }
            return new Result(warnings.isEmpty() ? BalanceCheck.PASSED : BalanceCheck.FAILED, opening, closing, warnings);
        }
    }
}

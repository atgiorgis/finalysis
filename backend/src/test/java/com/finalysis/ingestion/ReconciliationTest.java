package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.ingestion.Reconciliation.Previous;
import com.finalysis.ingestion.Reconciliation.RequestBalances;
import com.finalysis.ingestion.Reconciliation.Result;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Balance checks on hand-built statements; the import tests cover them end to end. */
class ReconciliationTest {

    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);

    // ------------------------------------------------------------------ section totals (ING-10)

    @Test
    void sectionTotalsThatMatchTheirRowsPass() {
        ParsedStatement statement = statement(null, null,
                Map.of(SourceSection.WITHDRAWAL, money("-60.00"), SourceSection.DEPOSIT, money("100.00")),
                row(1, "-10.00", SourceSection.WITHDRAWAL), row(2, "-50.00", SourceSection.WITHDRAWAL),
                row(3, "100.00", SourceSection.DEPOSIT));

        Result result = reconcile(statement);

        assertThat(result.check()).isEqualTo(BalanceCheck.PASSED);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void aSectionTotalThatDiffersFails() {
        ParsedStatement statement = statement(null, null, Map.of(SourceSection.WITHDRAWAL, money("-55.00")),
                row(1, "-10.00", SourceSection.WITHDRAWAL), row(2, "-50.00", SourceSection.WITHDRAWAL));

        Result result = reconcile(statement);

        assertThat(result.check()).isEqualTo(BalanceCheck.FAILED);
        assertThat(result.warnings()).containsExactly(
                "reconciliation failed: section WITHDRAWAL total: expected -60.00, actual -55.00, difference 5.00");
    }

    @Test
    void aMissingSectionWithANonZeroTotalFailsAndWithZeroPasses() {
        Result missing = reconcile(statement(null, null, Map.of(SourceSection.FEE, money("-12.00")),
                row(1, "-10.00", SourceSection.WITHDRAWAL)));
        Result zero = reconcile(statement(null, null, Map.of(SourceSection.FEE, money("0.00")),
                row(1, "-10.00", SourceSection.WITHDRAWAL)));

        assertThat(missing.check()).isEqualTo(BalanceCheck.FAILED);
        assertThat(missing.warnings()).containsExactly(
                "reconciliation failed: section FEE total (no FEE rows): expected 0.00, actual -12.00, difference -12.00");
        assertThat(zero.check()).isEqualTo(BalanceCheck.PASSED);
    }

    // ------------------------------------------------------------------ printed and request balances

    @Test
    void printedOpeningAndClosingMustAddUp() {
        assertThat(reconcile(statement(money("100.00"), money("90.00"), Map.of(), row(1, "-10.00"))).check())
                .isEqualTo(BalanceCheck.PASSED);
        assertThat(reconcile(statement(money("100.00"), money("80.00"), Map.of(), row(1, "-10.00"))).warnings())
                .containsExactly("reconciliation failed: printed balances: openingBalance + rows = closingBalance: "
                        + "expected 90.00, actual 80.00, difference -10.00");
    }

    @Test
    void requestBalancesMustAddUpAndAreStored() {
        ParsedStatement statement = statement(null, null, Map.of(), row(1, "-10.00"), row(2, "-2.50"));

        Result ok = Reconciliation.reconcile(statement, new RequestBalances(money("100.00"), money("87.50")), null);
        Result off = Reconciliation.reconcile(statement, new RequestBalances(money("100.00"), money("90.00")), null);
        Result one = Reconciliation.reconcile(statement, new RequestBalances(money("100.00"), null), null);

        assertThat(ok.check()).isEqualTo(BalanceCheck.PASSED);
        assertThat(ok.opening()).isEqualByComparingTo("100.00");
        assertThat(ok.closing()).isEqualByComparingTo("87.50");
        assertThat(off.warnings()).containsExactly("reconciliation failed: request balances: openingBalance + rows = "
                + "closingBalance: expected 87.50, actual 90.00, difference 2.50");
        assertThat(one.check()).isEqualTo(BalanceCheck.UNVERIFIED);
        assertThat(one.opening()).isEqualByComparingTo("100.00");
    }

    @Test
    void aRequestBalanceThatContradictsThePrintedOneFailsAndThePrintedIsKept() {
        Result result = Reconciliation.reconcile(statement(null, money("90.00"), Map.of(), row(1, "-10.00")),
                new RequestBalances(null, money("95.00")), null);

        assertThat(result.check()).isEqualTo(BalanceCheck.FAILED);
        assertThat(result.closing()).isEqualByComparingTo("90.00");
        assertThat(result.warnings()).containsExactly("reconciliation failed: request closingBalance against the "
                + "file's (the file's is kept): expected 90.00, actual 95.00, difference 5.00");
    }

    @Test
    void nothingToCheckIsUnverified() {
        Result result = reconcile(statement(null, null, Map.of(), row(1, "-10.00")));

        assertThat(result.check()).isEqualTo(BalanceCheck.UNVERIFIED);
        assertThat(result.warnings()).containsExactly(Reconciliation.UNVERIFIED_WARNING);
        assertThat(result.opening()).isNull();
        assertThat(result.closing()).isNull();
    }

    // ------------------------------------------------------------------ balance column

    @Test
    void balanceColumnDerivesOpeningAndClosing() {
        Result result = reconcile(statement(null, null, Map.of(),
                balanceRow(2, 1, "-10.00", "90.00"), balanceRow(3, 2, "-5.00", "85.00"), balanceRow(4, 3, "20.00", "105.00")));

        assertThat(result.check()).isEqualTo(BalanceCheck.PASSED);
        assertThat(result.opening()).isEqualByComparingTo("100.00");
        assertThat(result.closing()).isEqualByComparingTo("105.00");
    }

    @Test
    void newestFirstBalanceColumnIsCheckedInReverse() {
        Result result = reconcile(statement(null, null, Map.of(),
                balanceRow(2, 3, "20.00", "105.00"), balanceRow(3, 2, "-5.00", "85.00"), balanceRow(4, 1, "-10.00", "90.00")));

        assertThat(result.check()).isEqualTo(BalanceCheck.PASSED);
        assertThat(result.opening()).isEqualByComparingTo("100.00");
        assertThat(result.closing()).isEqualByComparingTo("105.00");
    }

    @Test
    void aWrongRunningBalanceNamesItsLine() {
        Result result = reconcile(statement(null, null, Map.of(),
                balanceRow(2, 1, "-10.00", "90.00"), balanceRow(3, 2, "-5.00", "86.00"), balanceRow(4, 3, "20.00", "106.00")));

        // Pairwise: only line 3 disagrees with the balance printed before it.
        assertThat(result.check()).isEqualTo(BalanceCheck.FAILED);
        assertThat(result.warnings()).containsExactly(
                "reconciliation failed: balance column, line 3: expected 85.00, actual 86.00, difference 1.00");
    }

    @Test
    void blankBalanceCellsCarryTheirAmountsToTheNextBalance() {
        Result result = reconcile(statement(null, null, Map.of(),
                balanceRow(2, 1, "-10.00", null), balanceRow(3, 2, "-5.00", "85.00"), balanceRow(4, 3, "-1.00", null),
                balanceRow(5, 4, "-2.00", "82.00"), balanceRow(6, 5, "-3.00", null)));

        assertThat(result.check()).isEqualTo(BalanceCheck.PASSED);
        assertThat(result.opening()).isEqualByComparingTo("100.00");
        assertThat(result.closing()).isEqualByComparingTo("79.00");
    }

    @Test
    void aPrintedOpeningBalanceMustAgreeWithTheBalanceColumn() {
        // A CSV balance-only row gives the opening balance; the balance column has to start from it.
        Result agreeing = reconcile(statement(money("100.00"), null, Map.of(),
                balanceRow(2, 1, "-10.00", "90.00"), balanceRow(3, 2, "-5.00", "85.00")));
        Result disagreeing = reconcile(statement(money("101.00"), null, Map.of(),
                balanceRow(2, 1, "-10.00", "90.00"), balanceRow(3, 2, "-5.00", "85.00")));

        assertThat(agreeing.check()).isEqualTo(BalanceCheck.PASSED);
        assertThat(agreeing.opening()).isEqualByComparingTo("100.00");
        assertThat(agreeing.closing()).isEqualByComparingTo("85.00");
        assertThat(disagreeing.check()).isEqualTo(BalanceCheck.FAILED);
        assertThat(disagreeing.warnings()).containsExactly("reconciliation failed: printed openingBalance against the "
                + "balance column: expected 100.00, actual 101.00, difference 1.00");
    }

    @Test
    void requestBalancesMustAgreeWithTheBalanceColumn() {
        ParsedStatement statement = statement(null, null, Map.of(), balanceRow(2, 1, "-10.00", "90.00"));

        Result result = Reconciliation.reconcile(statement, new RequestBalances(money("100.00"), money("95.00")), null);

        assertThat(result.check()).isEqualTo(BalanceCheck.FAILED);
        assertThat(result.warnings()).containsExactly(
                "reconciliation failed: request balances: openingBalance + rows = closingBalance: expected 90.00, "
                        + "actual 95.00, difference 5.00",
                "reconciliation failed: request closingBalance against the balance column: expected 90.00, "
                        + "actual 95.00, difference 5.00");
        assertThat(result.closing()).isEqualByComparingTo("90.00");
    }

    @Test
    void manyFailingRowsAreListedUpToTenThenCounted() {
        List<ParsedTxn> rows = new ArrayList<>();
        rows.add(balanceRow(2, 1, "-1.00", "99.00"));
        for (int i = 0; i < 12; i++) {
            rows.add(balanceRow(3 + i, 2, "-1.00", "50.00")); // every one disagrees with the one before
        }
        Result result = reconcile(statement(null, null, Map.of(), rows.toArray(ParsedTxn[]::new)));

        assertThat(result.warnings()).hasSize(11);
        assertThat(result.warnings().getLast()).isEqualTo("reconciliation failed: balance column: 2 more rows");
    }

    // ------------------------------------------------------------------ continuity

    @Test
    void continuityTakesThePreviousClosingAsTheOpening() {
        ParsedStatement statement = statement(null, money("90.00"), Map.of(), row(1, "-10.00"));

        Result ok = Reconciliation.reconcile(statement, RequestBalances.NONE, new Previous(7, money("100.00")));
        Result off = Reconciliation.reconcile(statement, RequestBalances.NONE, new Previous(7, money("110.00")));
        Result alone = Reconciliation.reconcile(statement, RequestBalances.NONE, null);

        assertThat(ok.check()).isEqualTo(BalanceCheck.PASSED);
        assertThat(ok.opening()).isEqualByComparingTo("100.00");
        assertThat(off.check()).isEqualTo(BalanceCheck.FAILED);
        assertThat(off.opening()).isNull();
        assertThat(off.warnings()).containsExactly("reconciliation failed: continuity: statement 7's closingBalance "
                + "+ rows = closingBalance: expected 100.00, actual 90.00, difference -10.00");
        // Never an opening derived from the closing alone.
        assertThat(alone.check()).isEqualTo(BalanceCheck.UNVERIFIED);
        assertThat(alone.opening()).isNull();
    }

    @Test
    void continuityAloneForALateArrival() {
        Result result = Reconciliation.continuity(money("-10.00"), money("90.00"), new Previous(7, money("100.00")));

        assertThat(result.check()).isEqualTo(BalanceCheck.PASSED);
        assertThat(result.opening()).isEqualByComparingTo("100.00");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void checksUseEveryParsedRow() {
        // Rows that are duplicates of an earlier import still count: they are on the statement.
        ParsedStatement statement = statement(null, money("85.00"), Map.of(), row(1, "-10.00"), row(2, "-5.00"));

        assertThat(Reconciliation.reconcile(statement, RequestBalances.NONE, new Previous(1, money("100.00"))).check())
                .isEqualTo(BalanceCheck.PASSED);
    }

    // ------------------------------------------------------------------ helpers

    private static Result reconcile(ParsedStatement statement) {
        return Reconciliation.reconcile(statement, RequestBalances.NONE, null);
    }

    private static ParsedStatement statement(BigDecimal opening, BigDecimal closing,
                                             Map<SourceSection, BigDecimal> sectionTotals, ParsedTxn... rows) {
        return new ParsedStatement(AccountHints.none(), APRIL_1, APRIL_1.plusDays(29), opening, closing, sectionTotals,
                List.of(rows), List.of());
    }

    private static ParsedTxn row(int line, String amount) {
        return row(line, amount, new BigDecimal(amount).signum() < 0 ? SourceSection.WITHDRAWAL : SourceSection.DEPOSIT);
    }

    private static ParsedTxn row(int line, String amount, SourceSection section) {
        return txn(line, APRIL_1, amount, section, null);
    }

    private static ParsedTxn balanceRow(int line, int day, String amount, String balance) {
        return txn(line, APRIL_1.plusDays(day - 1), amount,
                new BigDecimal(amount).signum() < 0 ? SourceSection.WITHDRAWAL : SourceSection.DEPOSIT,
                balance == null ? null : money(balance));
    }

    private static ParsedTxn txn(int line, LocalDate date, String amount, SourceSection section, BigDecimal balance) {
        return new ParsedTxn(date, date, null, "ROW " + line, amount, money(amount), section, null, null, line,
                "row " + line, balance);
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }
}

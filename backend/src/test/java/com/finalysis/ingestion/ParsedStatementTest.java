package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.account.AccountType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Validation shared by every parser's output (ParsedStatement, ParsedTxn, AccountHints, Money). */
class ParsedStatementTest {

    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);
    private static final LocalDate APRIL_30 = LocalDate.of(2026, 4, 30);

    @Test
    void amountsArePaddedToScaleTwo() {
        ParsedStatement statement = statement(new BigDecimal("100"), new BigDecimal("66.4"),
                Map.of(SourceSection.FEE, new BigDecimal("-12.5")), List.of(txn("-42.1")));

        assertThat(statement.openingBalance()).isEqualTo(new BigDecimal("100.00"));
        assertThat(statement.closingBalance()).isEqualTo(new BigDecimal("66.40"));
        assertThat(statement.sectionTotals().get(SourceSection.FEE)).isEqualTo(new BigDecimal("-12.50"));
        assertThat(statement.txns().getFirst().amount()).isEqualTo(new BigDecimal("-42.10"));
    }

    @Test
    void balancesAndSectionTotalsAreOptional() {
        ParsedStatement statement = statement(null, null, Map.of(), List.of());

        assertThat(statement.openingBalance()).isNull();
        assertThat(statement.closingBalance()).isNull();
        assertThat(statement.sectionTotals()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12.345", "0.001", "-1.999"})
    void rejectsMoreThanTwoDecimalPlacesInsteadOfRounding(String amount) {
        assertThatThrownBy(() -> txn(amount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than two decimal places");
        assertThatThrownBy(() -> statement(new BigDecimal(amount), null, Map.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openingBalance");
    }

    @Test
    void acceptsTheLargestNumeric12x2ValueAndRejectsAnythingLarger() {
        assertThat(txn("9999999999.99").amount()).isEqualTo(new BigDecimal("9999999999.99"));
        assertThat(txn("-9999999999.99").amount()).isEqualTo(new BigDecimal("-9999999999.99"));

        assertThatThrownBy(() -> txn("10000000000.00")).hasMessageContaining("NUMERIC(12,2)");
        assertThatThrownBy(() -> statement(null, null, Map.of(SourceSection.DEPOSIT, new BigDecimal("-1E+10")), List.of()))
                .hasMessageContaining("NUMERIC(12,2)");
    }

    @Test
    void rejectsNullSectionTotalKeyOrAmount() {
        Map<SourceSection, BigDecimal> nullAmount = new HashMap<>();
        nullAmount.put(SourceSection.FEE, null);
        Map<SourceSection, BigDecimal> nullKey = new HashMap<>();
        nullKey.put(null, BigDecimal.ONE);

        assertThatThrownBy(() -> statement(null, null, nullAmount, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> statement(null, null, nullKey, List.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsPeriodEndingBeforeItStarts() {
        assertThatThrownBy(() -> new ParsedStatement(AccountHints.none(), APRIL_30, APRIL_1,
                null, null, Map.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("periodEnd");
    }

    @Test
    void collectionsAreCopiedAndUnmodifiable() {
        List<ParsedTxn> txns = new ArrayList<>(List.of(txn("-1.00")));
        ParsedStatement statement = statement(null, null, Map.of(), txns);
        txns.clear();

        assertThat(statement.txns()).hasSize(1);
        assertThatThrownBy(() -> statement.txns().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> statement.sectionTotals().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> statement.warnings().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rawDescriptionIsKeptExactlyAndFeedsTheFingerprint() {
        String raw = "  CHECKCARD 0704 BLUE BEAN\n CAFE  ";
        ParsedTxn txn = new ParsedTxn(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 4), LocalDate.of(2026, 7, 6),
                raw, "-4.50", new BigDecimal("-4.50"), SourceSection.WITHDRAWAL, null, "FIT-1", 12, "line text");

        TransactionFingerprint.Input input = txn.fingerprintInput(7);

        assertThat(txn.rawDescription()).isEqualTo(raw);
        assertThat(input).isEqualTo(new TransactionFingerprint.Input(
                7, LocalDate.of(2026, 7, 6), new BigDecimal("-4.50"), raw, SourceSection.WITHDRAWAL));
    }

    @Test
    void rejectsInvalidTxnFields() {
        assertThatThrownBy(() -> new ParsedTxn(APRIL_1, APRIL_1, null, "X", "1", BigDecimal.ONE,
                SourceSection.CHECK, "1".repeat(21), null, 1, "X"))
                .hasMessageContaining("checkNumber");
        assertThatThrownBy(() -> new ParsedTxn(APRIL_1, APRIL_1, null, "X", "1", BigDecimal.ONE,
                SourceSection.DEPOSIT, null, null, 0, "X"))
                .hasMessageContaining("sourceLineNumber");
        assertThatThrownBy(() -> new ParsedTxn(APRIL_1, APRIL_1, null, null, "1", BigDecimal.ONE,
                SourceSection.DEPOSIT, null, null, 1, "X"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void accountHintsAcceptOnlyLastFourDigitsAndCurrencyCodes() {
        assertThat(new AccountHints("1001", AccountType.CHECKING, "USD").lastFour()).isEqualTo("1001");
        assertThat(AccountHints.none().lastFour()).isNull();

        // A full account number must never be carried as a hint.
        assertThatThrownBy(() -> new AccountHints("123456781001", null, null)).hasMessageContaining("lastFour");
        assertThatThrownBy(() -> new AccountHints("10a1", null, null)).hasMessageContaining("lastFour");
        assertThatThrownBy(() -> new AccountHints(null, null, "usd")).hasMessageContaining("currency");
    }

    private static ParsedStatement statement(BigDecimal opening, BigDecimal closing,
                                             Map<SourceSection, BigDecimal> totals, List<ParsedTxn> txns) {
        return new ParsedStatement(AccountHints.none(), APRIL_1, APRIL_30, opening, closing, totals, txns, List.of());
    }

    @Test
    void runningBalanceIsOptionalAndValidatedLikeAmounts() {
        assertThat(txn("-1.00").runningBalance()).isNull();
        assertThat(txnWithBalance("12.5").runningBalance()).isEqualTo(new BigDecimal("12.50"));
        assertThatThrownBy(() -> txnWithBalance("12.505")).hasMessageContaining("runningBalance");
    }

    private static ParsedTxn txnWithBalance(String balance) {
        return new ParsedTxn(APRIL_1, APRIL_1, null, "GREENLEAF MARKET", "-1.00", new BigDecimal("-1.00"),
                SourceSection.WITHDRAWAL, null, null, 2, "04/01/2026,GREENLEAF MARKET,-1.00", new BigDecimal(balance));
    }

    private static ParsedTxn txn(String amount) {
        return new ParsedTxn(APRIL_1, APRIL_1, null, "GREENLEAF MARKET", amount, new BigDecimal(amount),
                SourceSection.WITHDRAWAL, null, null, 2, "04/01/2026,GREENLEAF MARKET," + amount);
    }
}

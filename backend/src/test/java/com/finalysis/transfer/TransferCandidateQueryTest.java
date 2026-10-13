package com.finalysis.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.support.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each rule of phase-3c.md §2.1 on both sides of its boundary, in plain SQL fixtures. Results are
 * filtered to the txns each test creates, so rows other tests leave behind can't affect them.
 */
@IntegrationTest
@Transactional // each test's inserts roll back
class TransferCandidateQueryTest {

    private static final int WINDOW = 5;
    private static final LocalDate DAY = LocalDate.of(2026, 4, 10);

    private final TransferCandidateQuery query;
    private final JdbcTemplate jdbc;
    private final TransferRows rows;

    private long checking;
    private long savings;
    private long card;

    TransferCandidateQueryTest(TransferCandidateQuery query, JdbcTemplate jdbc) {
        this.query = query;
        this.jdbc = jdbc;
        this.rows = new TransferRows(jdbc);
    }

    @BeforeEach
    void setUp() {
        checking = rows.account("CHECKING", "1001");
        savings = rows.account("SAVINGS", "1002");
        card = rows.account("CREDIT_CARD", "1003");
    }

    @Test
    void findsEqualAndOppositeAmountsOnTwoAccounts() {
        long out = rows.txn(checking, "-250.00", DAY);
        long in = rows.txn(savings, "250.00", DAY.plusDays(1));

        assertThat(candidates()).containsExactly(new CandidatePair(
                new CandidatePair.Side(out, checking, DAY, null),
                new CandidatePair.Side(in, savings, DAY.plusDays(1), null)));
    }

    // Rule 1: equal and opposite, exact cents ------------------------------------------------------

    @Test
    void ignoresAmountsOneCentApart() {
        rows.txn(checking, "-250.00", DAY);
        rows.txn(savings, "250.01", DAY);

        assertThat(candidates()).isEmpty();
    }

    @Test
    void ignoresAmountsOfTheSameSign() {
        rows.txn(checking, "-250.00", DAY);
        rows.txn(savings, "-250.00", DAY);
        rows.txn(checking, "250.00", DAY.minusMonths(1)); // outside the window of both
        rows.txn(savings, "300.00", DAY);
        rows.txn(card, "300.00", DAY);

        assertThat(candidates()).isEmpty();
    }

    @Test
    void neverPairsZeroAmounts() {
        rows.txn(checking, "0.00", DAY);
        rows.txn(savings, "0.00", DAY);

        assertThat(candidates()).isEmpty();
    }

    // Rule 2: different accounts -------------------------------------------------------------------

    @Test
    void ignoresAReversalWithinOneAccount() {
        rows.txn(checking, "-250.00", DAY);
        rows.txn(checking, "250.00", DAY);

        assertThat(candidates()).isEmpty();
    }

    @Test
    void pairsTwoAccountsOfTheSameType() {
        long otherChecking = rows.account("CHECKING", "1004");
        rows.txn(checking, "-250.00", DAY);
        rows.txn(otherChecking, "250.00", DAY);

        assertThat(candidates()).hasSize(1);
    }

    // Rule 3: effective dates within the window, either way ----------------------------------------

    @ParameterizedTest
    @CsvSource({"0, true", "5, true", "6, false", "-5, true", "-6, false"})
    void pairsWithinTheWindowEitherWay(int inDaysAfterOut, boolean expected) {
        rows.txn(checking, "-250.00", DAY);
        rows.txn(card, "250.00", DAY.plusDays(inDaysAfterOut), "PAYMENT");

        assertThat(candidates()).hasSize(expected ? 1 : 0);
    }

    @Test
    void usesPostDatesWhenPresent() {
        // Purchase dates ten days apart, posted one day apart: a candidate.
        long out = rows.txn(checking, "-250.00", DAY, DAY.plusDays(9), "WITHDRAWAL");
        long in = rows.txn(card, "250.00", DAY.plusDays(10), DAY.plusDays(10), "PAYMENT");
        // Same purchase date, posted six days apart: not a candidate.
        rows.txn(checking, "-80.00", DAY, DAY, "WITHDRAWAL");
        rows.txn(card, "80.00", DAY, DAY.plusDays(6), "PAYMENT");

        assertThat(candidates()).singleElement().satisfies(pair -> {
            assertThat(pair.out().txnId()).isEqualTo(out);
            assertThat(pair.in().txnId()).isEqualTo(in);
            assertThat(pair.out().effectiveDate()).isEqualTo(DAY.plusDays(9));
            assertThat(pair.daysApart()).isEqualTo(1);
        });
    }

    @Test
    void fallsBackToTheTxnDateOnEitherSide() {
        rows.txn(checking, "-250.00", DAY.minusDays(3), DAY, "WITHDRAWAL");
        rows.txn(savings, "250.00", DAY.plusDays(5)); // no post date: 5 days after the out's post date

        assertThat(candidates()).singleElement()
                .satisfies(pair -> assertThat(pair.daysApart()).isEqualTo(5));
    }

    @Test
    void takesTheWindowAsAParameter() {
        rows.txn(checking, "-250.00", DAY);
        rows.txn(savings, "250.00", DAY.plusDays(2));

        assertThat(candidates(1)).isEmpty();
        assertThat(candidates(2)).hasSize(1);
    }

    @Test
    void windowZeroMeansTheSameDay() {
        rows.txn(checking, "-250.00", DAY);
        rows.txn(savings, "250.00", DAY);
        rows.txn(checking, "-90.00", DAY);
        rows.txn(savings, "90.00", DAY.plusDays(1));

        assertThat(candidates(0)).singleElement()
                .satisfies(pair -> assertThat(pair.daysApart()).isZero());
    }

    @Test
    void rejectsANegativeWindow() {
        assertThatThrownBy(() -> query.find(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    // Rule 4: account types ------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "CHECKING, SAVINGS, true",
            "SAVINGS, CHECKING, true",
            "CHECKING, CREDIT_CARD, true",
            "SAVINGS, CREDIT_CARD, true",
            "CREDIT_CARD, CHECKING, false",
            "CREDIT_CARD, SAVINGS, false",
            "CREDIT_CARD, CREDIT_CARD, false"})
    void allowsMoneyOutOfCheckingOrSavingsOnly(String outType, String inType, boolean expected) {
        rows.txn(rows.account(outType, "2001"), "-75.00", DAY);
        rows.txn(rows.account(inType, "2002"), "75.00", DAY);

        assertThat(candidates()).hasSize(expected ? 1 : 0);
    }

    // Rule 5: fees and interest --------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"FEE", "INTEREST"})
    void ignoresFeesAndInterestOnTheOutSide(String section) {
        rows.txn(checking, "-12.00", DAY, null, section);
        rows.txn(savings, "12.00", DAY);

        assertThat(candidates()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FEE", "INTEREST"})
    void ignoresFeesAndInterestOnTheInSide(String section) {
        rows.txn(checking, "-12.00", DAY);
        rows.txn(savings, "12.00", DAY, null, section);

        assertThat(candidates()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"WITHDRAWAL", "CHECK", "PAYMENT", "PURCHASE", "OTHER"})
    void acceptsEveryOtherSection(String section) {
        rows.txn(checking, "-12.00", DAY, null, section);
        rows.txn(card, "12.00", DAY, null, section);

        assertThat(candidates()).hasSize(1);
    }

    // Rule 6: the user's decisions -----------------------------------------------------------------

    @Test
    void ignoresAnExcludedOutSide() {
        long out = rows.txn(checking, "-250.00", DAY);
        rows.txn(savings, "250.00", DAY);
        rows.exclude(out);

        assertThat(candidates()).isEmpty();
    }

    @Test
    void ignoresAnExcludedInSide() {
        rows.txn(checking, "-250.00", DAY);
        long in = rows.txn(savings, "250.00", DAY);
        rows.exclude(in);

        assertThat(candidates()).isEmpty();
    }

    @Test
    void ignoresARejectedPairButNotItsOtherPairs() {
        long out = rows.txn(checking, "-250.00", DAY);
        long rejected = rows.txn(savings, "250.00", DAY);
        long other = rows.txn(card, "250.00", DAY.plusDays(2), "PAYMENT");
        rows.reject(out, rejected);

        assertThat(candidates()).extracting(pair -> List.of(pair.out().txnId(), pair.in().txnId()))
                .containsExactly(List.of(out, other));
    }

    // Rule 7: never link a txn twice ---------------------------------------------------------------

    @Test
    void ignoresAnOutSideOnAMatchedLink() {
        long out = rows.txn(checking, "-250.00", DAY);
        rows.userLink(out, rows.txn(savings, "250.00", DAY), "MATCHED");
        rows.txn(card, "250.00", DAY, "PAYMENT");

        assertThat(candidates()).isEmpty();
    }

    @Test
    void ignoresAnInSideOnAMatchedLink() {
        long in = rows.txn(savings, "250.00", DAY);
        rows.userLink(rows.txn(checking, "-250.00", DAY), in, "MATCHED");
        rows.txn(rows.account("CHECKING", "1004"), "-250.00", DAY);

        assertThat(candidates()).isEmpty();
    }

    @Test
    void offersThePresentOutSideOfAnUnmatchedLink() {
        long out = rows.txn(checking, "-250.00", DAY);
        long linkId = rows.userLink(out, null, "UNMATCHED");
        long in = rows.txn(savings, "250.00", DAY);

        assertThat(candidates()).singleElement().satisfies(pair -> {
            assertThat(pair.out()).isEqualTo(new CandidatePair.Side(out, checking, DAY, linkId));
            assertThat(pair.in()).isEqualTo(new CandidatePair.Side(in, savings, DAY, null));
        });
    }

    @Test
    void offersThePresentInSideOfAnUnmatchedLink() {
        long out = rows.txn(checking, "-250.00", DAY);
        long in = rows.txn(savings, "250.00", DAY);
        long linkId = rows.userLink(null, in, "UNMATCHED");

        assertThat(candidates()).singleElement().satisfies(pair -> {
            assertThat(pair.out()).isEqualTo(new CandidatePair.Side(out, checking, DAY, null));
            assertThat(pair.in()).isEqualTo(new CandidatePair.Side(in, savings, DAY, linkId));
        });
    }

    /** Both are already transfers; joining them would replace two user decisions with one link. */
    @Test
    void ignoresAPairWhoseSidesAreBothOnUnmatchedLinks() {
        rows.userLink(rows.txn(checking, "-250.00", DAY), null, "UNMATCHED");
        rows.userLink(null, rows.txn(savings, "250.00", DAY), "UNMATCHED");

        assertThat(candidates()).isEmpty();
    }

    /** The schema allows a negative txn as the in side of a one-sided link; it can't be completed as an out. */
    @Test
    void ignoresAnUnmatchedLinkWhoseSideHasTheWrongSign() {
        long out = rows.txn(checking, "-250.00", DAY);
        rows.userLink(null, out, "UNMATCHED");
        rows.txn(savings, "250.00", DAY);

        assertThat(candidates()).isEmpty();
    }

    // Everything else ------------------------------------------------------------------------------

    @Test
    void includesRowsOfStatementsThatNeedReview() {
        long statement = rows.statement(checking, "NEEDS_REVIEW");
        rows.txn(checking, statement, "-250.00", DAY, null, "WITHDRAWAL");
        rows.txn(savings, "250.00", DAY);

        assertThat(candidates()).hasSize(1);
    }

    @Test
    void returnsEveryPairOfAGroupInIdOrder() {
        long out = rows.txn(checking, "-200.00", DAY);
        long savingsIn = rows.txn(savings, "200.00", DAY);
        long cardIn = rows.txn(card, "200.00", DAY.plusDays(1), "PAYMENT");
        long secondOut = rows.txn(rows.account("SAVINGS", "1004"), "-200.00", DAY);

        assertThat(candidates()).extracting(pair -> List.of(pair.out().txnId(), pair.in().txnId()))
                .containsExactly(List.of(out, savingsIn), List.of(out, cardIn),
                        List.of(secondOut, savingsIn), List.of(secondOut, cardIn));
    }

    /** §2.6: detection never reads descriptions or any other text, so replacing all of it changes nothing. */
    @Test
    void ignoresEveryTextColumn() {
        rows.txn(checking, "-250.00", DAY);
        rows.txn(savings, "250.00", DAY);
        rows.txn(checking, "-200.00", DAY.plusDays(3));
        rows.txn(savings, "200.00", DAY.plusDays(3));
        rows.txn(card, "200.00", DAY.plusDays(4), "PAYMENT");
        rows.txn(checking, "-42.00", DAY.plusDays(7));
        rows.txn(card, "42.00", DAY.plusDays(9), "PURCHASE");
        List<CandidatePair> before = candidates();

        for (long id : rows.txns()) {
            jdbc.update("""
                    UPDATE txn SET raw_description = ?, merchant = ?, counterparty = ?, external_id = ?,
                                   check_number = ?, source_line_text = ?, printed_amount = ?
                    WHERE id = ?
                    """, scrambled(), scrambled(), scrambled(), scrambled(), "9999", scrambled(), scrambled(), id);
        }
        jdbc.update("UPDATE account SET name = ?, institution = ? WHERE id IN (?, ?, ?)",
                scrambled(), scrambled(), checking, savings, card);

        assertThat(before).hasSize(4);
        assertThat(candidates()).isEqualTo(before);
    }

    // Helpers ------------------------------------------------------------------------------------

    private List<CandidatePair> candidates() {
        return candidates(WINDOW);
    }

    private List<CandidatePair> candidates(int windowDays) {
        return query.find(windowDays).stream()
                .filter(pair -> rows.txns().contains(pair.out().txnId()) && rows.txns().contains(pair.in().txnId()))
                .toList();
    }

    private static String scrambled() {
        return UUID.randomUUID().toString();
    }
}

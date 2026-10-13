package com.finalysis.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.transfer.CandidatePair.Side;
import com.finalysis.transfer.TransferResolver.Completion;
import com.finalysis.transfer.TransferResolver.Resolution;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The choice among candidates (phase-3c.md §2.3, §2.4), without a database. */
class TransferResolverTest {

    private static final long CHECKING = 1;
    private static final long SAVINGS = 2;
    private static final long CARD = 3;

    private final TransferResolver resolver = new TransferResolver();

    @Test
    void nothingToResolve() {
        Resolution resolution = resolver.resolve(List.of());

        assertThat(resolution.newLinks()).isEmpty();
        assertThat(resolution.completions()).isEmpty();
        assertThat(resolution.forReview()).isEmpty();
    }

    @Test
    void linksOneOutWithOneIn() {
        CandidatePair pair = pair(side(10, CHECKING, 2), side(20, SAVINGS, 2));

        Resolution resolution = resolver.resolve(List.of(pair));

        assertThat(resolution.newLinks()).containsExactly(pair);
        assertThat(resolution.completions()).isEmpty();
        assertThat(resolution.forReview()).isEmpty();
    }

    @Test
    void resolvesSeparateGroupsOnTheirOwn() {
        CandidatePair savingsTransfer = pair(side(10, CHECKING, 2), side(20, SAVINGS, 2));
        CandidatePair cardPayment = pair(side(11, CHECKING, 5), side(30, CARD, 8));

        Resolution resolution = resolver.resolve(List.of(cardPayment, savingsTransfer));

        assertThat(resolution.newLinks()).containsExactly(savingsTransfer, cardPayment);
    }

    /** Two 250.00 transfers to savings in one week: every out is a candidate with every in. */
    @Test
    void pairsEqualNumbersBetweenTwoAccountsInDateOrder() {
        Side out1 = side(10, CHECKING, 2);
        Side out2 = side(11, CHECKING, 4);
        Side in1 = side(21, SAVINGS, 2); // higher id, earlier date: date order wins over id order
        Side in2 = side(20, SAVINGS, 4);

        Resolution resolution = resolver.resolve(
                List.of(pair(out1, in1), pair(out1, in2), pair(out2, in1), pair(out2, in2)));

        assertThat(resolution.newLinks()).containsExactly(pair(out1, in1), pair(out2, in2));
        assertThat(resolution.forReview()).isEmpty();
    }

    @Test
    void breaksDateTiesByTxnId() {
        Side out1 = side(10, CHECKING, 2);
        Side out2 = side(11, CHECKING, 2);
        Side in1 = side(20, SAVINGS, 3);
        Side in2 = side(21, SAVINGS, 3);

        Resolution resolution = resolver.resolve(
                List.of(pair(out2, in2), pair(out1, in2), pair(out2, in1), pair(out1, in1)));

        assertThat(resolution.newLinks()).containsExactly(pair(out1, in1), pair(out2, in2));
    }

    /** A chain (o1–i1, o2–i1, o2–i2) between the same two accounts is still k:k. */
    @Test
    void pairsAChainBetweenTwoAccountsInDateOrder() {
        Side out1 = side(10, CHECKING, 1);
        Side out2 = side(11, CHECKING, 6);
        Side in1 = side(20, SAVINGS, 5);
        Side in2 = side(21, SAVINGS, 10);

        Resolution resolution = resolver.resolve(List.of(pair(out1, in1), pair(out2, in1), pair(out2, in2)));

        assertThat(resolution.newLinks()).containsExactly(pair(out1, in1), pair(out2, in2));
    }

    /** The date-order pairing o1–i1 isn't a candidate (here: the user rejected it), so nothing is guessed. */
    @Test
    void reviewsEqualNumbersWhenDateOrderPairsANonCandidate() {
        Side out1 = side(10, CHECKING, 2);
        Side out2 = side(11, CHECKING, 4);
        Side in1 = side(20, SAVINGS, 2);
        Side in2 = side(21, SAVINGS, 4);
        List<CandidatePair> pairs = List.of(pair(out1, in2), pair(out2, in1), pair(out2, in2));

        Resolution resolution = resolver.resolve(pairs);

        assertThat(resolution.newLinks()).isEmpty();
        assertThat(resolution.forReview()).containsExactly(new CandidateGroup(pairs));
    }

    /** −200.00 on checking with +200.00 on savings and +200.00 on the card: guessing would be wrong half the time. */
    @Test
    void reviewsOneOutWithTwoIns() {
        Side out = side(10, CHECKING, 2);
        List<CandidatePair> pairs = List.of(pair(out, side(20, SAVINGS, 2)), pair(out, side(30, CARD, 4)));

        Resolution resolution = resolver.resolve(pairs);

        assertThat(resolution.newLinks()).isEmpty();
        assertThat(resolution.completions()).isEmpty();
        assertThat(resolution.forReview()).containsExactly(new CandidateGroup(pairs));
        assertThat(resolution.forReview().getFirst().txnIds()).containsExactly(10L, 20L, 30L);
    }

    @Test
    void reviewsTwoOutsWithOneIn() {
        Side in = side(20, SAVINGS, 3);
        List<CandidatePair> pairs = List.of(pair(side(10, CHECKING, 2), in), pair(side(11, CHECKING, 4), in));

        Resolution resolution = resolver.resolve(pairs);

        assertThat(resolution.newLinks()).isEmpty();
        assertThat(resolution.forReview()).containsExactly(new CandidateGroup(pairs));
    }

    /** k:k, but the outs are on two accounts: the pairing would decide which account paid. */
    @Test
    void reviewsEqualNumbersAcrossMoreThanTwoAccounts() {
        Side checkingOut = side(10, CHECKING, 2);
        Side savingsOut = side(11, SAVINGS, 2);
        Side in1 = side(30, CARD, 3);
        Side in2 = side(31, CARD, 4);
        List<CandidatePair> pairs = List.of(pair(checkingOut, in1), pair(checkingOut, in2),
                pair(savingsOut, in1), pair(savingsOut, in2));

        Resolution resolution = resolver.resolve(pairs);

        assertThat(resolution.newLinks()).isEmpty();
        assertThat(resolution.forReview()).hasSize(1);
        assertThat(resolution.forReview().getFirst().pairs()).containsExactlyElementsOf(pairs);
    }

    /** A chain that reaches a third account is one group, reviewed whole. */
    @Test
    void reviewsAChainAcrossAccountsAsOneGroup() {
        Side out1 = side(10, CHECKING, 2);
        Side out2 = side(11, CHECKING, 4);
        Side savingsIn = side(20, SAVINGS, 3);
        Side cardIn = side(30, CARD, 6);
        List<CandidatePair> pairs = List.of(pair(out1, savingsIn), pair(out2, savingsIn), pair(out2, cardIn));

        Resolution resolution = resolver.resolve(pairs);

        assertThat(resolution.newLinks()).isEmpty();
        assertThat(resolution.forReview()).containsExactly(new CandidateGroup(pairs));
    }

    @Test
    void completesAnUnmatchedOutWithItsOnlyCounterpart() {
        CandidatePair pair = pair(unmatched(side(10, CHECKING, 2), 500), side(20, SAVINGS, 3));

        Resolution resolution = resolver.resolve(List.of(pair));

        assertThat(resolution.newLinks()).isEmpty();
        assertThat(resolution.completions()).containsExactly(new Completion(500, 20));
    }

    @Test
    void completesAnUnmatchedInWithItsOnlyCounterpart() {
        CandidatePair pair = pair(side(10, CHECKING, 2), unmatched(side(20, SAVINGS, 3), 500));

        Resolution resolution = resolver.resolve(List.of(pair));

        assertThat(resolution.newLinks()).isEmpty();
        assertThat(resolution.completions()).containsExactly(new Completion(500, 10));
    }

    @Test
    void reviewsAnUnmatchedSideWithTwoCounterparts() {
        Side out = unmatched(side(10, CHECKING, 2), 500);
        List<CandidatePair> pairs = List.of(pair(out, side(20, SAVINGS, 2)), pair(out, side(30, CARD, 3)));

        Resolution resolution = resolver.resolve(pairs);

        assertThat(resolution.completions()).isEmpty();
        assertThat(resolution.forReview()).containsExactly(new CandidateGroup(pairs));
    }

    /** k:k would pair it, but the user's link is completed only when the group is that single pair (§2.4). */
    @Test
    void reviewsEqualNumbersThatIncludeAnUnmatchedSide() {
        Side out1 = unmatched(side(10, CHECKING, 2), 500);
        Side out2 = side(11, CHECKING, 4);
        Side in1 = side(20, SAVINGS, 2);
        Side in2 = side(21, SAVINGS, 4);
        List<CandidatePair> pairs = List.of(pair(out1, in1), pair(out1, in2), pair(out2, in1), pair(out2, in2));

        Resolution resolution = resolver.resolve(pairs);

        assertThat(resolution.newLinks()).isEmpty();
        assertThat(resolution.completions()).isEmpty();
        assertThat(resolution.forReview()).containsExactly(new CandidateGroup(pairs));
    }

    @Test
    void ordersEverythingByTxnIdWhateverTheInputOrder() {
        List<CandidatePair> pairs = new ArrayList<>(mixedInput());
        Resolution expected = resolver.resolve(pairs);
        Random random = new Random(42);

        for (int i = 0; i < 20; i++) {
            Collections.shuffle(pairs, random);
            assertThat(resolver.resolve(pairs)).isEqualTo(expected);
        }
        assertThat(expected.newLinks()).extracting(pair -> pair.out().txnId()).isSorted();
        assertThat(expected.forReview()).extracting(group -> group.txnIds().first()).isSorted();
        assertThat(expected.newLinks()).hasSize(3);
        assertThat(expected.completions()).hasSize(1);
        assertThat(expected.forReview()).hasSize(2);
    }

    @Test
    void ignoresRepeatedPairs() {
        CandidatePair pair = pair(side(10, CHECKING, 2), side(20, SAVINGS, 2));

        assertThat(resolver.resolve(List.of(pair, pair)).newLinks()).containsExactly(pair);
    }

    /** Every case at once: a 1:1, a 2:2, a completion, a 1:2 and a 2:1 group. */
    private static List<CandidatePair> mixedInput() {
        Side kOut1 = side(40, CHECKING, 2);
        Side kOut2 = side(41, CHECKING, 3);
        Side kIn1 = side(42, SAVINGS, 2);
        Side kIn2 = side(43, SAVINGS, 3);
        Side forkOut = side(50, CHECKING, 6);
        Side joinIn = side(60, SAVINGS, 9);
        return List.of(
                pair(side(1, CHECKING, 1), side(2, SAVINGS, 1)),
                pair(kOut1, kIn1), pair(kOut1, kIn2), pair(kOut2, kIn1), pair(kOut2, kIn2),
                pair(unmatched(side(30, CHECKING, 4), 900), side(31, CARD, 6)),
                pair(forkOut, side(51, SAVINGS, 6)), pair(forkOut, side(52, CARD, 7)),
                pair(side(61, CHECKING, 8), joinIn), pair(side(62, CHECKING, 9), joinIn));
    }

    private static Side side(long txnId, long accountId, int dayOfApril) {
        return new Side(txnId, accountId, LocalDate.of(2026, 4, dayOfApril), null);
    }

    private static Side unmatched(Side side, long linkId) {
        return new Side(side.txnId(), side.accountId(), side.effectiveDate(), linkId);
    }

    private static CandidatePair pair(Side out, Side in) {
        return new CandidatePair(out, in);
    }
}

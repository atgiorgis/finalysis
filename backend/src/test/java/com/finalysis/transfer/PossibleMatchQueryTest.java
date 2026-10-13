package com.finalysis.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.finalysis.support.IntegrationTest;
import com.finalysis.transfer.PossibleMatchQuery.PossibleMatch;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Possible matches between one-sided links (phase-3c.md §4.5): equal and opposite, different accounts,
 * within the window, not rejected, and nothing else. Results are filtered to the txns each test creates.
 */
@IntegrationTest
@Transactional // each test's inserts roll back
class PossibleMatchQueryTest {

    private static final int WINDOW = 5;
    private static final LocalDate DAY = LocalDate.of(2026, 4, 10);

    private final PossibleMatchQuery query;
    private final JdbcTemplate jdbc;
    private final TransferRows rows;

    private long checking;
    private long savings;
    private long card;

    PossibleMatchQueryTest(PossibleMatchQuery query, JdbcTemplate jdbc) {
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
    void listsAnOutOnlyAndAnInOnlyLinkThatWouldMatch() {
        long out = rows.txn(checking, "-160.00", DAY);
        long in = rows.txn(savings, "160.00", DAY.plusDays(2));
        long outLink = rows.userLink(out, null, "UNMATCHED");
        long inLink = rows.userLink(null, in, "UNMATCHED");
        OffsetDateTime newer = OffsetDateTime.of(2026, 5, 1, 12, 0, 0, 0, ZoneOffset.UTC);
        jdbc.update("UPDATE transfer_link SET created_at = ? WHERE id = ?", newer.minusDays(1), outLink);
        jdbc.update("UPDATE transfer_link SET created_at = ? WHERE id = ?", newer, inLink);

        List<PossibleMatch> found = matches(WINDOW);

        assertThat(found).hasSize(1);
        PossibleMatch match = found.getFirst();
        assertThat(match.outLinkId()).isEqualTo(outLink);
        assertThat(match.inLinkId()).isEqualTo(inLink);
        assertThat(match.pair().out().txnId()).isEqualTo(out);
        assertThat(match.pair().in().txnId()).isEqualTo(in);
        assertThat(match.pair().daysApart()).isEqualTo(2);
        assertThat(match.since()).isEqualTo(newer);
    }

    @ParameterizedTest
    @CsvSource({"5, true", "6, false", "-5, true", "-6, false"})
    void listsOnlyWithinTheWindowEitherWay(int inDaysAfterOut, boolean expected) {
        oneSidedPair(checking, "-160.00", savings, "160.00", inDaysAfterOut);

        assertThat(matches(WINDOW)).hasSize(expected ? 1 : 0);
    }

    @Test
    void takesTheWindowAsAParameter() {
        oneSidedPair(checking, "-160.00", savings, "160.00", 3);

        assertThat(matches(2)).isEmpty();
        assertThat(matches(3)).hasSize(1);
        assertThatThrownBy(() -> query.find(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void needsEqualAndOppositeAmounts() {
        oneSidedPair(checking, "-160.00", savings, "160.01", 0);

        assertThat(matches(WINDOW)).isEmpty();
    }

    @Test
    void needsDifferentAccounts() {
        oneSidedPair(checking, "-160.00", checking, "160.00", 0);

        assertThat(matches(WINDOW)).isEmpty();
    }

    @Test
    void ignoresARejectedPair() {
        long[] pair = oneSidedPair(checking, "-160.00", savings, "160.00", 0);
        rows.reject(pair[0], pair[1]);

        assertThat(matches(WINDOW)).isEmpty();
    }

    /** The user has already said both are transfers: card money out and FEE rows count here. */
    @Test
    void appliesNoAccountTypeOrSectionRule() {
        long out = rows.txn(card, "-75.00", DAY, "FEE");
        long in = rows.txn(checking, "75.00", DAY);
        rows.userLink(out, null, "UNMATCHED");
        rows.userLink(null, in, "UNMATCHED");

        assertThat(matches(WINDOW)).hasSize(1);
    }

    @Test
    void ignoresMatchedLinksAndOneSidedLinksOfOneDirection() {
        rows.userLink(rows.txn(checking, "-160.00", DAY), null, "UNMATCHED");
        rows.userLink(rows.txn(savings, "-160.00", DAY), null, "UNMATCHED");
        rows.userLink(rows.txn(checking, "-90.00", DAY), rows.txn(savings, "90.00", DAY), "MATCHED");
        rows.userLink(null, rows.txn(card, "90.00", DAY, "PAYMENT"), "UNMATCHED");

        assertThat(matches(WINDOW)).isEmpty();
    }

    @Test
    void ignoresAWrongSignOneSidedLink() {
        rows.userLink(rows.txn(checking, "-160.00", DAY), null, "UNMATCHED");
        rows.userLink(null, rows.txn(savings, "-160.00", DAY), "UNMATCHED"); // the schema allows a negative in side

        assertThat(matches(WINDOW)).isEmpty();
    }

    @Test
    void listsEachPairOfOneTxnWithSeveralMatches() {
        long out = rows.txn(checking, "-160.00", DAY);
        long outLink = rows.userLink(out, null, "UNMATCHED");
        long first = rows.userLink(null, rows.txn(savings, "160.00", DAY), "UNMATCHED");
        long second = rows.userLink(null, rows.txn(card, "160.00", DAY, "PAYMENT"), "UNMATCHED");

        assertThat(matches(WINDOW)).extracting(PossibleMatch::outLinkId, PossibleMatch::inLinkId)
                .containsExactly(tuple(outLink, first), tuple(outLink, second));
    }

    @Test
    void ignoresEveryTextColumn() {
        oneSidedPair(checking, "-160.00", savings, "160.00", 1);
        List<PossibleMatch> before = matches(WINDOW);

        for (long id : rows.txns()) {
            jdbc.update("UPDATE txn SET raw_description = ?, merchant = ?, counterparty = ? WHERE id = ?",
                    scrambled(), scrambled(), scrambled(), id);
        }
        jdbc.update("UPDATE account SET name = ?, institution = ? WHERE id IN (?, ?, ?)",
                scrambled(), scrambled(), checking, savings, card);

        assertThat(before).hasSize(1);
        assertThat(matches(WINDOW)).isEqualTo(before);
    }

    // Helpers ------------------------------------------------------------------------------------

    /** Two one-sided links; returns the out and in txn ids. */
    private long[] oneSidedPair(long outAccount, String outAmount, long inAccount, String inAmount, int inDaysAfterOut) {
        long out = rows.txn(outAccount, outAmount, DAY);
        long in = rows.txn(inAccount, inAmount, DAY.plusDays(inDaysAfterOut));
        rows.userLink(out, null, "UNMATCHED");
        rows.userLink(null, in, "UNMATCHED");
        return new long[] {out, in};
    }

    private List<PossibleMatch> matches(int windowDays) {
        return query.find(windowDays).stream()
                .filter(match -> rows.txns().contains(match.pair().out().txnId())
                        && rows.txns().contains(match.pair().in().txnId()))
                .toList();
    }

    private static String scrambled() {
        return UUID.randomUUID().toString();
    }
}

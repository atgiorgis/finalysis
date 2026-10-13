package com.finalysis.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.support.IntegrationTest;
import com.finalysis.transfer.CompetingCandidateQuery.CompetingPair;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Competing candidates for early links (phase-3c.md §4.6): rule 7 waived for the link's own side only,
 * every other rule as in {@link TransferCandidateQueryTest}. Results are filtered to the txns each test
 * creates.
 */
@IntegrationTest
@Transactional // each test's inserts roll back
class CompetingCandidateQueryTest {

    private static final int WINDOW = 5;
    private static final LocalDate DAY = LocalDate.of(2026, 4, 10);

    private final CompetingCandidateQuery query;
    private final JdbcTemplate jdbc;
    private final TransferRows rows;

    private long checking;
    private long savings;
    private long card;

    CompetingCandidateQueryTest(CompetingCandidateQuery query, JdbcTemplate jdbc) {
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
    void listsATxnCompetingForTheInSideOfAnEarlyLink() {
        long out = rows.txn(checking, "-300.00", DAY);
        long savingsIn = rows.txn(savings, "300.00", DAY.plusDays(1));
        long link = rows.autoLink(out, savingsIn);
        long cardIn = rows.txn(card, "300.00", DAY.plusDays(3), "PAYMENT");

        List<CompetingPair> found = competing();

        assertThat(found).hasSize(1);
        CompetingPair pair = found.getFirst();
        assertThat(pair.linkId()).isEqualTo(link);
        assertThat(pair.pair().out().txnId()).isEqualTo(out);
        assertThat(pair.pair().out().unmatchedLinkId()).as("as if the link didn't exist").isNull();
        assertThat(pair.pair().in().txnId()).isEqualTo(cardIn);
        assertThat(pair.pair().in().accountId()).isEqualTo(card);
        assertThat(pair.pair().daysApart()).isEqualTo(3);
    }

    @Test
    void listsATxnCompetingForTheOutSideOfAnEarlyLink() {
        long out = rows.txn(checking, "-300.00", DAY);
        long cardIn = rows.txn(card, "300.00", DAY.plusDays(2), "PAYMENT");
        long link = rows.autoLink(out, cardIn);
        long savingsOut = rows.txn(savings, "-300.00", DAY.plusDays(1));

        assertThat(pairs()).containsExactly(List.of(link, savingsOut, cardIn));
    }

    @Test
    void neverListsTheLinksOwnPair() {
        rows.autoLink(rows.txn(checking, "-300.00", DAY), rows.txn(savings, "300.00", DAY));

        assertThat(competing()).isEmpty();
    }

    @Test
    void listsEveryCompetitorOfOneLink() {
        long out = rows.txn(checking, "-300.00", DAY);
        long link = rows.autoLink(out, rows.txn(savings, "300.00", DAY));
        long first = rows.txn(card, "300.00", DAY.plusDays(1), "PAYMENT");
        long second = rows.txn(card, "300.00", DAY.plusDays(2), "PAYMENT");

        assertThat(pairs()).containsExactly(List.of(link, out, first), List.of(link, out, second));
    }

    @Test
    void ignoresConfirmedAndUserLinks() {
        long confirmedOut = rows.txn(checking, "-300.00", DAY);
        long confirmed = rows.autoLink(confirmedOut, rows.txn(savings, "300.00", DAY));
        jdbc.update("UPDATE transfer_link SET confirmed_at = now() WHERE id = ?", confirmed);
        rows.userLink(rows.txn(checking, "-410.00", DAY), rows.txn(savings, "410.00", DAY), "MATCHED");
        rows.txn(card, "300.00", DAY.plusDays(1), "PAYMENT");
        rows.txn(card, "410.00", DAY.plusDays(1), "PAYMENT");

        assertThat(competing()).isEmpty();
    }

    @Test
    void ignoresACompetitorOnAnotherMatchedLink() {
        long firstOut = rows.txn(checking, "-300.00", DAY);
        rows.autoLink(firstOut, rows.txn(savings, "300.00", DAY));
        rows.autoLink(rows.txn(savings, "-300.00", DAY.plusDays(1)), rows.txn(card, "300.00", DAY.plusDays(1),
                "PAYMENT"));

        assertThat(competing()).as("each would match the other's side, but both are on links").isEmpty();
    }

    @Test
    void listsTheMatchingSideOfAnUnmatchedLinkAsACompetitor() {
        long out = rows.txn(checking, "-300.00", DAY);
        long link = rows.autoLink(out, rows.txn(savings, "300.00", DAY));
        long cardIn = rows.txn(card, "300.00", DAY.plusDays(1), "PAYMENT");
        long oneSided = rows.userLink(null, cardIn, "UNMATCHED");

        List<CompetingPair> found = competing();

        assertThat(found).extracting(CompetingPair::linkId).containsExactly(link);
        assertThat(found.getFirst().pair().in().unmatchedLinkId()).isEqualTo(oneSided);
    }

    @Test
    void ignoresAnUnmatchedLinksSideOfTheWrongDirection() {
        rows.autoLink(rows.txn(checking, "-300.00", DAY), rows.txn(savings, "300.00", DAY));
        rows.userLink(rows.txn(card, "300.00", DAY.plusDays(1), "PAYMENT"), null, "UNMATCHED"); // schema allows it

        assertThat(competing()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"window", "excluded", "rejected", "fee", "same-account", "amount"})
    void appliesEveryOtherRuleToTheCompetitor(String broken) {
        long out = rows.txn(checking, "-300.00", DAY);
        rows.autoLink(out, rows.txn(savings, "300.00", DAY));
        long competitor = switch (broken) {
            case "window" -> rows.txn(card, "300.00", DAY.plusDays(WINDOW + 1), "PAYMENT");
            case "fee" -> rows.txn(card, "300.00", DAY.plusDays(1), "FEE");
            case "same-account" -> rows.txn(checking, "300.00", DAY.plusDays(1));
            case "amount" -> rows.txn(card, "300.01", DAY.plusDays(1), "PAYMENT");
            default -> rows.txn(card, "300.00", DAY.plusDays(1), "PAYMENT");
        };
        if (broken.equals("excluded")) {
            rows.exclude(competitor);
        } else if (broken.equals("rejected")) {
            rows.reject(out, competitor);
        }

        assertThat(competing()).isEmpty();
    }

    @Test
    void ignoresACardOutCompetingForABankIn() {
        long out = rows.txn(checking, "-300.00", DAY);
        rows.autoLink(out, rows.txn(savings, "300.00", DAY));
        rows.txn(card, "-300.00", DAY.plusDays(1), "PURCHASE"); // rule 4: card money out is never an out side

        assertThat(competing()).isEmpty();
    }

    /** §2.6 holds here too: the query selects no text column, so replacing all of it changes nothing. */
    @Test
    void ignoresEveryTextColumn() {
        long out = rows.txn(checking, "-300.00", DAY);
        rows.autoLink(out, rows.txn(savings, "300.00", DAY));
        rows.txn(card, "300.00", DAY.plusDays(1), "PAYMENT");
        List<List<Long>> before = pairs();

        for (long id : rows.txns()) {
            jdbc.update("UPDATE txn SET raw_description = ?, merchant = ?, counterparty = ?, external_id = ? WHERE id = ?",
                    scrambled(), scrambled(), scrambled(), scrambled(), id);
        }
        jdbc.update("UPDATE account SET name = ?, institution = ? WHERE id IN (?, ?, ?)",
                scrambled(), scrambled(), checking, savings, card);

        assertThat(before).hasSize(1);
        assertThat(pairs()).isEqualTo(before);
    }

    // Helpers ------------------------------------------------------------------------------------

    private List<CompetingPair> competing() {
        return query.find(WINDOW).stream()
                .filter(found -> rows.txns().contains(found.pair().out().txnId())
                        && rows.txns().contains(found.pair().in().txnId()))
                .toList();
    }

    /** {@code [linkId, outTxnId, inTxnId]} per pair. */
    private List<List<Long>> pairs() {
        return competing().stream()
                .map(found -> List.of(found.linkId(), found.pair().out().txnId(), found.pair().in().txnId()))
                .toList();
    }

    private static String scrambled() {
        return UUID.randomUUID().toString();
    }
}

package com.finalysis.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Detection end to end over the database (phase-3c.md §2.3 to §2.5) and {@code POST
 * /api/transfers/detect}. The rules are in TransferCandidateQueryTest and the choices in
 * TransferResolverTest; the sample-data answer key is in TransferDetectionTest.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional // each test's inserts and links roll back
class TransferDetectorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 4, 10);

    private final TransferDetector detector;
    private final TransferLock lock;
    private final TransferCandidateQuery query;
    private final TransferResolver resolver;
    private final TransferLinkRepository links;
    private final EntityManager entityManager;
    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final TransferRows rows;

    private long checking;
    private long savings;
    private long card;

    TransferDetectorTest(TransferDetector detector, TransferLock lock, TransferCandidateQuery query,
                         TransferResolver resolver, TransferLinkRepository links, EntityManager entityManager,
                         MockMvc mockMvc, JsonMapper jsonMapper, JdbcTemplate jdbc) {
        this.detector = detector;
        this.lock = lock;
        this.query = query;
        this.resolver = resolver;
        this.links = links;
        this.entityManager = entityManager;
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
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
    void linksACandidatePairAsAnUnconfirmedAutoLink() {
        long out = rows.txn(checking, "-250.00", DAY);
        long in = rows.txn(savings, "250.00", DAY.plusDays(1));
        long purchase = rows.txn(checking, "-42.17", DAY);

        assertThat(detector.detect()).isEqualTo(new DetectionResult(1, 0, 0));

        assertThat(link(out)).containsEntry("out_txn_id", out).containsEntry("in_txn_id", in)
                .containsEntry("status", "MATCHED").containsEntry("method", "AUTO")
                .containsEntry("confirmed_at", null);
        assertThat(rows.isTransfer(out)).isTrue();
        assertThat(rows.isTransfer(in)).isTrue();
        assertThat(rows.isTransfer(purchase)).isFalse();
    }

    @Test
    void aSecondRunCreatesNothing() {
        rows.txn(checking, "-250.00", DAY);
        rows.txn(savings, "250.00", DAY);
        detector.detect();
        long linkCount = links.count();

        assertThat(detector.detect()).isEqualTo(new DetectionResult(0, 0, 0));
        assertThat(links.count()).isEqualTo(linkCount);
    }

    @Test
    void pairsEqualTransfersBetweenTwoAccountsInDateOrder() {
        long out1 = rows.txn(checking, "-250.00", DAY);
        long out2 = rows.txn(checking, "-250.00", DAY.plusDays(2));
        long in2 = rows.txn(savings, "250.00", DAY.plusDays(2));
        long in1 = rows.txn(savings, "250.00", DAY);

        assertThat(detector.detect()).isEqualTo(new DetectionResult(2, 0, 0));

        assertThat(link(out1)).containsEntry("in_txn_id", in1);
        assertThat(link(out2)).containsEntry("in_txn_id", in2);
    }

    /** §2.3 rule 3: −200.00 with +200.00 on savings and on the card is left to the user. */
    @Test
    void leavesAnAmbiguousGroupUnlinked() {
        long out = rows.txn(checking, "-200.00", DAY);
        long savingsIn = rows.txn(savings, "200.00", DAY);
        long cardIn = rows.txn(card, "200.00", DAY.plusDays(1), "PAYMENT");

        assertThat(detector.detect()).isEqualTo(new DetectionResult(0, 0, 1));

        assertThat(links.count()).isZero();
        assertThat(List.of(out, savingsIn, cardIn)).noneMatch(rows::isTransfer);
    }

    /** §2.4: the pairing is new and unreviewed, so the user's one-sided link becomes AUTO and unconfirmed. */
    @Test
    void completesAnUnmatchedLinkWhenItsCounterpartArrives() {
        long out = rows.txn(checking, "-250.00", DAY);
        long linkId = rows.userLink(out, null, "UNMATCHED");
        long in = rows.txn(savings, "250.00", DAY.plusDays(2));

        assertThat(detector.detect()).isEqualTo(new DetectionResult(0, 1, 0));

        assertThat(link(out)).containsEntry("id", linkId).containsEntry("in_txn_id", in)
                .containsEntry("status", "MATCHED").containsEntry("method", "AUTO")
                .containsEntry("confirmed_at", null);
        assertThat(rows.isTransfer(in)).isTrue();
        assertThat(links.count()).isEqualTo(1);
    }

    @Test
    void completesAnUnmatchedIncomingLink() {
        long out = rows.txn(checking, "-250.00", DAY);
        long in = rows.txn(savings, "250.00", DAY);
        long linkId = rows.userLink(null, in, "UNMATCHED");

        assertThat(detector.detect()).isEqualTo(new DetectionResult(0, 1, 0));

        assertThat(link(in)).containsEntry("id", linkId).containsEntry("out_txn_id", out)
                .containsEntry("status", "MATCHED");
        assertThat(rows.isTransfer(out)).isTrue();
    }

    @Test
    void neverChangesAnExistingMatchedLink() {
        long out = rows.txn(checking, "-250.00", DAY);
        long in = rows.txn(savings, "250.00", DAY);
        long linkId = rows.userLink(out, in, "MATCHED");
        rows.txn(card, "250.00", DAY, "PAYMENT");

        assertThat(detector.detect()).isEqualTo(new DetectionResult(0, 0, 0));

        assertThat(link(out)).containsEntry("id", linkId).containsEntry("method", "USER")
                .containsEntry("in_txn_id", in);
    }

    @Test
    void usesTheConfiguredWindow() {
        long out = rows.txn(checking, "-250.00", DAY);
        rows.txn(savings, "250.00", DAY.plusDays(2));
        TransferDetector oneDayWindow = new TransferDetector(lock, query, resolver, links, entityManager,
                new TransferProperties(1));

        assertThat(oneDayWindow.detect()).isEqualTo(new DetectionResult(0, 0, 0));
        assertThat(detector.detect()).isEqualTo(new DetectionResult(1, 0, 0)); // default: 5 days
        assertThat(rows.isTransfer(out)).isTrue();
    }

    /** §2.5: the advisory lock is held until this transaction ends, so a second run waits for it. */
    @Test
    void holdsTheTransferLockUntilTheTransactionEnds() {
        detector.detect();

        Integer held = jdbc.queryForObject("""
                SELECT count(*) FROM pg_locks
                WHERE locktype = 'advisory' AND pid = pg_backend_pid() AND granted
                  AND classid = ? AND objid = ? AND objsubid = 1
                """, Integer.class, TransferLock.KEY >>> 32, TransferLock.KEY & 0xFFFF_FFFFL);
        assertThat(held).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theLockNeedsATransaction() {
        try {
            assertThatThrownBy(lock::acquire).isInstanceOf(IllegalTransactionStateException.class);
        } finally {
            // Without a test transaction, setUp's accounts were committed; nothing rolls them back.
            jdbc.update("DELETE FROM account WHERE id IN (?, ?, ?)", checking, savings, card);
        }
    }

    @Test
    void detectEndpointReturnsTheCounts() throws Exception {
        rows.txn(checking, "-250.00", DAY);
        rows.txn(savings, "250.00", DAY);
        rows.txn(checking, "-200.00", DAY);
        rows.txn(savings, "200.00", DAY);
        rows.txn(card, "200.00", DAY, "PAYMENT");

        String body = mockMvc.perform(post("/api/transfers/detect"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> result = jsonMapper.readValue(body, new TypeReference<>() {
        });
        assertThat(result).isEqualTo(Map.of("linksCreated", 1, "unmatchedCompleted", 0, "groupsForReview", 1));
    }

    @Test
    void detectEndpointOnlyAcceptsPost() throws Exception {
        mockMvc.perform(get("/api/transfers/detect"))
                .andExpect(status().isMethodNotAllowed());
    }

    /** The link the txn is on, as stored. */
    private Map<String, Object> link(long txnId) {
        return jdbc.queryForMap("""
                SELECT id, out_txn_id, in_txn_id, status, method, confirmed_at FROM transfer_link
                WHERE out_txn_id = ? OR in_txn_id = ?
                """, txnId, txnId);
    }
}

package com.finalysis.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.finalysis.support.IntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Concurrent creates of the same account (V12): the database decides, so exactly one succeeds and
 * every other gets 409 {@code account-exists} with the winner's id. No test transaction: each
 * request commits for real, and every account of {@link #INSTITUTION}, in any case, is deleted after each test.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AccountConcurrentCreateTest {

    private static final String INSTITUTION = "Concurrency Test Bank";
    private static final String BODY = """
            {"name": "Race Checking", "institution": "%s", "type": "CHECKING", "lastFour": "7777"}
            """.formatted(INSTITUTION);

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    AccountConcurrentCreateTest(MockMvc mockMvc, JsonMapper jsonMapper, JdbcTemplate jdbc, DataSource dataSource) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    @AfterEach
    void deleteAccounts() {
        jdbc.update("DELETE FROM account WHERE lower(btrim(institution)) = lower(?)", INSTITUTION);
    }

    @Test
    void createWaitingOnACommittedInsertGets409WithItsId() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            long winner = insert(other);

            CompletableFuture<MockHttpServletResponse> request = CompletableFuture.supplyAsync(this::create);
            awaitBlockedOnLock();
            other.commit();

            MockHttpServletResponse response = request.get(10, TimeUnit.SECONDS);
            assertThat(response.getStatus()).isEqualTo(409);
            Map<String, Object> problem = toMap(response.getContentAsString());
            assertThat(problem).containsEntry("type", "urn:finalysis:problem:account-exists");
            assertThat(((Number) problem.get("accountId")).longValue()).isEqualTo(winner);
        }
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void createWaitingOnARolledBackInsertSucceeds() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            insert(other);

            CompletableFuture<MockHttpServletResponse> request = CompletableFuture.supplyAsync(this::create);
            awaitBlockedOnLock();
            other.rollback();

            assertThat(request.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(201);
        }
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void racingCreatesGiveOneCreatedAndTheRestConflicts() throws Exception {
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return create();
                }));
            }
            List<MockHttpServletResponse> responses = new ArrayList<>();
            for (Future<MockHttpServletResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }

            List<MockHttpServletResponse> created = responses.stream().filter(r -> r.getStatus() == 201).toList();
            assertThat(created).hasSize(1);
            long id = id(created.getFirst());
            assertThat(responses).filteredOn(r -> r.getStatus() != 201).hasSize(threads - 1)
                    .allSatisfy(r -> {
                        assertThat(r.getStatus()).isEqualTo(409);
                        assertThat(((Number) toMap(r.getContentAsString()).get("accountId")).longValue()).isEqualTo(id);
                    });
        } finally {
            pool.shutdownNow();
        }
        assertThat(count()).isEqualTo(1);
    }

    private MockHttpServletResponse create() {
        try {
            return mockMvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andReturn().getResponse();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Inserts the same account (institution in another case) on {@code connection}, uncommitted. */
    private static long insert(Connection connection) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO account (name, institution, type, last_four)
                VALUES ('Other Session', ?, 'CHECKING', '7777') RETURNING id""")) {
            insert.setString(1, INSTITUTION.toUpperCase() + " ");
            try (ResultSet rows = insert.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    /** Waits until some session waits for a lock: the request blocked on the uncommitted index entry. */
    private void awaitBlockedOnLock() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class) == 0) {
            assertThat(Instant.now()).as("request blocked on the other session's insert").isBefore(deadline);
            Thread.sleep(20);
        }
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM account WHERE lower(btrim(institution)) = lower(?)",
                Integer.class, INSTITUTION);
    }

    private long id(MockHttpServletResponse response) throws Exception {
        return ((Number) toMap(response.getContentAsString()).get("id")).longValue();
    }

    private Map<String, Object> toMap(String json) {
        return jsonMapper.readValue(json, new TypeReference<>() { });
    }
}

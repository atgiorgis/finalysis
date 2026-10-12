package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Two overlapping imports to one account at the same time must not insert any row twice. */
@IntegrationTest
@AutoConfigureMockMvc
class StatementImportConcurrencyTest {

    private final JsonMapper jsonMapper;
    private final ImportTestSupport support;

    StatementImportConcurrencyTest(MockMvc mockMvc, JsonMapper jsonMapper, AccountRepository accounts, JdbcTemplate jdbc) {
        this.jsonMapper = jsonMapper;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    @RepeatedTest(3)
    void concurrentOverlappingImportsInsertEachRowOnce() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        String april = new String(ImportTestSupport.sample("ofx/northfield-checking_2026-04.ofx"), StandardCharsets.ISO_8859_1);
        // Two downloads of the same statement: different bytes (server timestamp), same rows.
        List<byte[]> files = List.of(
                april.getBytes(StandardCharsets.ISO_8859_1),
                april.replace("<DTSERVER>20260501080000", "<DTSERVER>20260502093000").getBytes(StandardCharsets.ISO_8859_1));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<MvcResult>> results = new ArrayList<>();
        try {
            for (int i = 0; i < files.size(); i++) {
                byte[] file = files.get(i);
                String name = "april-" + i + ".ofx";
                results.add(pool.submit(() -> {
                    start.await();
                    return support.upload(checking, name, file).andReturn();
                }));
            }
            start.countDown();

            int inserted = 0;
            int duplicates = 0;
            for (Future<MvcResult> result : results) {
                MvcResult response = result.get(60, TimeUnit.SECONDS);
                assertThat(response.getResponse().getStatus()).as(response.getResponse().getContentAsString()).isEqualTo(201);
                JsonNode summary = jsonMapper.readTree(response.getResponse().getContentAsString());
                inserted += summary.get("inserted").asInt();
                duplicates += summary.get("duplicates").asInt();
            }

            assertThat(inserted).isEqualTo(10);
            assertThat(duplicates).isEqualTo(10);
            assertThat(support.count("SELECT count(*) FROM txn WHERE account_id = ?", checking)).isEqualTo(10);
            assertThat(support.count("SELECT count(DISTINCT fingerprint) FROM txn WHERE account_id = ?", checking)).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }
}

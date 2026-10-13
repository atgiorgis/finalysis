package com.finalysis.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.finalysis.account.Account;
import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Helpers for import tests that commit for real (no test transaction), so locks and concurrent
 * imports behave as in production. Every account created here is deleted, with its statements,
 * transactions, unparsed uploads and CSV mapping, by {@link #deleteCreatedAccounts()}.
 */
public class ImportTestSupport {

    public static final Path SAMPLE_DATA = Path.of("..", "sample-data");

    private final MockMvc mockMvc;
    private final AccountRepository accounts;
    private final JdbcTemplate jdbc;
    private final List<Long> created = new ArrayList<>();

    public ImportTestSupport(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    public long account(AccountType type, String lastFour) {
        return account(type, lastFour, "Test " + lastFour, "Test Bank");
    }

    public long account(AccountType type, String lastFour, String name, String institution) {
        long id = accounts.save(new Account(name, institution, type, lastFour)).getId();
        created.add(id);
        return id;
    }

    public void deleteCreatedAccounts() {
        for (long id : created) {
            jdbc.update("DELETE FROM unparsed_upload WHERE account_id = ?", id);
            jdbc.update("DELETE FROM txn WHERE account_id = ?", id);
            jdbc.update("DELETE FROM statement WHERE account_id = ?", id);
            jdbc.update("DELETE FROM csv_mapping WHERE account_id = ?", id);
            jdbc.update("DELETE FROM account WHERE id = ?", id);
        }
        created.clear();
    }

    public ResultActions upload(long accountId, String fileName, byte[] bytes) throws Exception {
        return upload(accountId, fileName, bytes, Map.of());
    }

    public ResultActions upload(long accountId, String fileName, byte[] bytes, Map<String, String> params) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/accounts/{id}/imports", accountId)
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", bytes));
        params.forEach(request::param);
        return mockMvc.perform(request);
    }

    public ResultActions preview(long accountId, String fileName, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart("/api/accounts/{id}/imports/preview", accountId)
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", bytes)));
    }

    public ResultActions putMapping(long accountId, String json) throws Exception {
        return mockMvc.perform(put("/api/accounts/{id}/csv-mapping", accountId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    public static byte[] sample(String relativePath) {
        try {
            return Files.readAllBytes(SAMPLE_DATA.resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Answer-key rows of one CSV statement file: [file, account, date, description, amount, ...]. */
    public static List<String[]> answerKey(String statementFile) {
        try {
            return Files.readAllLines(SAMPLE_DATA.resolve("answer-key.csv")).stream().skip(1)
                    .map(line -> line.split(",", -1))
                    .filter(c -> c[0].equals(statementFile))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static BigDecimal sum(List<String[]> answerKeyRows) {
        return answerKeyRows.stream().map(c -> new BigDecimal(c[4])).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public long firstStatementId(long accountId) {
        Long id = jdbc.queryForObject("SELECT min(id) FROM statement WHERE account_id = ?", Long.class, accountId);
        return id == null ? -1 : id;
    }

    public int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }
}

package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.finalysis.account.Account;
import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.support.TestcontainersConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Uploads through a real Tomcat, where multipart parsing happens (MockMvc skips it). The
 * multipart location is a fresh directory; each time the import service is called, while the
 * request's parts are still held, a spy counts the files in it ({@link #filesSeen}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
abstract class MultipartUploadProbe {

    private static final Path UPLOADS = createDirectory();
    private static final String BOUNDARY = "finalysis-test-boundary";

    private final int port;
    private final AccountRepository accounts;
    private final JdbcTemplate jdbc;
    private final List<Long> created = new ArrayList<>();
    /** Files in the multipart location, counted each time the service is called. */
    protected final List<Long> filesSeen = new ArrayList<>();

    @MockitoSpyBean
    private StatementImportService imports;

    MultipartUploadProbe(int port, AccountRepository accounts, JdbcTemplate jdbc) {
        this.port = port;
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    @DynamicPropertySource
    static void multipartLocation(DynamicPropertyRegistry registry) {
        registry.add("spring.servlet.multipart.location", UPLOADS::toString);
    }

    @BeforeEach
    void probeTheMultipartLocation() {
        doAnswer(invocation -> {
            filesSeen.add(countFiles());
            return invocation.callRealMethod();
        }).when(imports).importStatement(any());
        doAnswer(invocation -> {
            filesSeen.add(countFiles());
            return invocation.callRealMethod();
        }).when(imports).preview(anyLong(), any(), any());
    }

    @AfterEach
    void deleteAccounts() {
        for (long id : created) {
            jdbc.update("DELETE FROM unparsed_upload WHERE account_id = ?", id);
            jdbc.update("DELETE FROM txn WHERE account_id = ?", id);
            jdbc.update("DELETE FROM statement WHERE account_id = ?", id);
            jdbc.update("DELETE FROM account WHERE id = ?", id);
        }
    }

    // ------------------------------------------------------------------ helpers

    protected long account() {
        long id = accounts.save(new Account("Test 1001", "Test Bank", AccountType.CHECKING, "1001")).getId();
        created.add(id);
        return id;
    }

    protected HttpResponse<String> post(String path, byte[] file) throws IOException, InterruptedException {
        return post(path, "statement.csv", file);
    }

    protected HttpResponse<String> post(String path, String fileName, byte[] file) throws IOException, InterruptedException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(file);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /** A CSV of exactly 10 MB: 1 KB rows, the last one shortened to fit. */
    protected static byte[] tenMegabyteCsv() {
        StringBuilder csv = new StringBuilder("Date,Description,Amount\n");
        String rowStart = "2026-04-01,";
        String rowEnd = ",-1.00\n";
        while (UploadCheck.MAX_BYTES - csv.length() >= 2048) {
            csv.append(rowStart).append("D".repeat(1024 - rowStart.length() - rowEnd.length())).append(rowEnd);
        }
        long last = UploadCheck.MAX_BYTES - csv.length();
        csv.append(rowStart).append("D".repeat((int) last - rowStart.length() - rowEnd.length())).append(rowEnd);
        byte[] bytes = csv.toString().getBytes(StandardCharsets.US_ASCII);
        assertThat(bytes).hasSize((int) UploadCheck.MAX_BYTES);
        return bytes;
    }

    protected static long countFiles() {
        try (Stream<Path> files = Files.list(UPLOADS)) {
            return files.count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path createDirectory() {
        try {
            Path directory = Files.createTempDirectory("finalysis-multipart");
            directory.toFile().deleteOnExit();
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

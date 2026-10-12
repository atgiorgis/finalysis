package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.support.ImportTestSupport;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SEC-10 through the real multipart path: with the threshold above the size limit, uploads never
 * reach disk ({@link MultipartDiskControlTest} shows the probe sees them when they do), and the
 * multipart limit lets exactly 10 MB through.
 */
class MultipartDiskTest extends MultipartUploadProbe {

    private final JdbcTemplate jdbc;

    MultipartDiskTest(@LocalServerPort int port, AccountRepository accounts, JdbcTemplate jdbc) {
        super(port, accounts, jdbc);
        this.jdbc = jdbc;
    }

    @Test
    void importsAndPreviewsNeverWriteTheUploadToDisk() throws Exception {
        long checking = account();
        byte[] april = ImportTestSupport.sample("ofx/northfield-checking_2026-04.ofx");

        assertThat(post("/api/accounts/" + checking + "/imports", april).statusCode()).isEqualTo(201);
        assertThat(post("/api/accounts/" + checking + "/imports/preview", tenMegabyteCsv()).statusCode()).isEqualTo(200);

        assertThat(filesSeen).containsExactly(0L, 0L);
        assertThat(countFiles()).isZero();
    }

    /** The multipart limit (not only the check in code) lets exactly 10 MB through and refuses one byte more. */
    @Test
    void tenMegabytesPassTheMultipartLimitAndOneByteMoreIs413() throws Exception {
        long checking = account();
        byte[] atLimit = tenMegabyteCsv();
        byte[] overLimit = Arrays.copyOf(atLimit, atLimit.length + 1);
        overLimit[overLimit.length - 1] = '\n';

        assertThat(post("/api/accounts/" + checking + "/imports/preview", atLimit).statusCode()).isEqualTo(200);
        for (String path : List.of("/imports", "/imports/preview")) {
            HttpResponse<String> response = post("/api/accounts/" + checking + path, overLimit);
            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(response.body()).contains("urn:finalysis:problem:payload-too-large");
        }
        // The over-limit uploads were refused before any controller ran.
        assertThat(filesSeen).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM statement WHERE account_id = ?", Integer.class, checking)).isZero();
    }
}

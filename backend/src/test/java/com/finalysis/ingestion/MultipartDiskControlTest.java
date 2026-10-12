package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.support.ImportTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** The control for {@link MultipartDiskTest}: with a zero threshold, the probe sees the upload on disk. */
@TestPropertySource(properties = "spring.servlet.multipart.file-size-threshold=0")
class MultipartDiskControlTest extends MultipartUploadProbe {

    MultipartDiskControlTest(@LocalServerPort int port, AccountRepository accounts, JdbcTemplate jdbc) {
        super(port, accounts, jdbc);
    }

    @Test
    void theProbeSeesPartsWrittenToDisk() throws Exception {
        long checking = account();

        assertThat(post("/api/accounts/" + checking + "/imports/preview",
                ImportTestSupport.sample("ofx/northfield-checking_2026-04.ofx")).statusCode()).isEqualTo(200);

        assertThat(filesSeen).singleElement().satisfies(count -> assertThat(count).isPositive());
    }
}

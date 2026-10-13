package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.support.ImportTestSupport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SEC-10 through the real multipart path: with the threshold above the size limit, uploads never
 * reach disk ({@link MultipartDiskControlTest} shows the probe sees them when they do), and the
 * multipart limit lets exactly 10 MB through. PDF uploads (Phase 3b step 8) also leave no PDFBox
 * scratch file and no font cache.
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

    /**
     * A recognized layout (422 while parsing is paused), an unrecognized one (held, 202) and previews
     * of both: the upload never reaches disk, PDFBox writes no scratch file (its names start with
     * "PDFBox") to the temp directory, and its font cache in the home directory is not created or
     * changed. That cache would be written by PDFBox's default font mapper, which scans system fonts.
     */
    @Test
    void pdfUploadsWriteNoScratchFileAndNoFontCache() throws Exception {
        long checking = account();
        byte[] recognized = ImportTestSupport.sample("pdf/northfield-checking_2026-04.pdf");
        byte[] unrecognized = ImportTestSupport.sample("pdf/edge-cases/pdf-unrecognized-layout.pdf");
        Path fontCache = Path.of(System.getProperty("pdfbox.fontcache", System.getProperty("user.home")), ".pdfbox.cache");
        FileState fontCacheBefore = FileState.of(fontCache);
        Set<String> pdfboxTempFilesBefore = pdfboxTempFiles();

        String imports = "/api/accounts/" + checking + "/imports";
        assertThat(post(imports, "april.pdf", recognized).statusCode()).isEqualTo(422);
        assertThat(post(imports, "unknown.pdf", unrecognized).statusCode()).isEqualTo(202);
        assertThat(post(imports + "/preview", "april.pdf", recognized).statusCode()).isEqualTo(422);
        assertThat(post(imports + "/preview", "unknown.pdf", unrecognized).statusCode()).isEqualTo(200);

        assertThat(filesSeen).containsExactly(0L, 0L, 0L, 0L);
        assertThat(countFiles()).isZero();
        assertThat(pdfboxTempFiles()).isEqualTo(pdfboxTempFilesBefore);
        assertThat(FileState.of(fontCache)).isEqualTo(fontCacheBefore);
        assertThat(FontMappers.instance().getClass().getSimpleName()).isEqualTo("NoSystemFontsMapper");
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

    private static Set<String> pdfboxTempFiles() {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).contains("pdfbox"))
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether a file exists, and if so its size and modification time. */
    private record FileState(boolean exists, long size, FileTime modified) {

        static FileState of(Path file) throws IOException {
            return Files.exists(file)
                    ? new FileState(true, Files.size(file), Files.getLastModifiedTime(file))
                    : new FileState(false, 0, null);
        }
    }
}

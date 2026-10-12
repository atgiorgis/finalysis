package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UploadCheckTest {

    @Test
    void fileNamesLoseTheirPathAndControlCharacters() {
        assertThat(UploadCheck.cleanFileName("C:\\Users\\me\\Downloads\\april.ofx")).isEqualTo("april.ofx");
        assertThat(UploadCheck.cleanFileName("../../etc/april.ofx")).isEqualTo("april.ofx");
        assertThat(UploadCheck.cleanFileName("apr\nil\u0000.ofx ")).isEqualTo("april.ofx");
        assertThat(UploadCheck.cleanFileName(null)).isEmpty();
        assertThat(UploadCheck.cleanFileName("a".repeat(300) + ".ofx")).hasSize(255);
    }

    @ParameterizedTest
    @ValueSource(strings = {"april.ofx", "APRIL.QFX", "export.csv", "export.Txt", "a.b.ofx"})
    void allowedExtensions(String name) {
        assertThatCode(() -> UploadCheck.checkFileName(name)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"april.pdf", "april.xlsx", "april", "april.ofx.exe", ".", ""})
    void otherExtensionsAreUnsupported(String name) {
        assertThatThrownBy(() -> UploadCheck.checkFileName(name))
                .isInstanceOfSatisfying(ImportRejectedException.class,
                        e -> assertThat(e.getProblem()).isEqualTo(ImportProblem.UNSUPPORTED_FORMAT));
    }

    @Test
    void textInUtf8OrWindows1252IsAccepted() {
        assertThatCode(() -> UploadCheck.checkContent("Date,Desc\r\n04/01/2026,CAFÉ\t1\f".getBytes(StandardCharsets.UTF_8)))
                .doesNotThrowAnyException();
        assertThatCode(() -> UploadCheck.checkContent("CAFÉ €".getBytes(Charset.forName("windows-1252"))))
                .doesNotThrowAnyException();
    }

    @Test
    void binaryAndInvalidTextAreUnsupported() {
        assertRejected(new byte[] {'a', 0, 'b'}, "not a text file");
        assertRejected(new byte[] {'a', 0x1B, 'b'}, "not a text file");
        // 0x81 is neither valid UTF-8 on its own nor defined in windows-1252.
        assertRejected(new byte[] {'a', (byte) 0x81, 'b'}, "not valid UTF-8 or windows-1252");
        assertRejected("%PDF-1.4".getBytes(StandardCharsets.US_ASCII), "PDF");
        assertRejected(new byte[] {'P', 'K', 3, 4}, "ZIP");
        assertRejected(new byte[0], "empty");
    }

    @Test
    void sizeLimitIsTenMegabytes() {
        assertThatCode(() -> UploadCheck.checkSize(10L * 1024 * 1024)).doesNotThrowAnyException();
        assertThatThrownBy(() -> UploadCheck.checkSize(10L * 1024 * 1024 + 1))
                .isInstanceOfSatisfying(ImportRejectedException.class,
                        e -> assertThat(e.getProblem()).isEqualTo(ImportProblem.PAYLOAD_TOO_LARGE));
    }

    private static void assertRejected(byte[] bytes, String detail) {
        assertThatThrownBy(() -> UploadCheck.checkContent(bytes))
                .isInstanceOf(ImportRejectedException.class)
                .hasMessageContaining(detail);
    }
}

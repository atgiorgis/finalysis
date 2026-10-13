package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

    @Test
    void longNamesKeepTheirExtension() {
        String cleaned = UploadCheck.cleanFileName("a".repeat(300) + ".ofx");

        assertThat(cleaned).isEqualTo("a".repeat(251) + ".ofx");
        assertThatCode(() -> UploadCheck.checkFileName(cleaned)).doesNotThrowAnyException();
        // A surrogate pair at the cut is dropped whole, never split.
        assertThat(UploadCheck.cleanFileName("a".repeat(250) + "\uD83D\uDE00" + "b".repeat(50) + ".csv"))
                .isEqualTo("a".repeat(250) + ".csv");
        // Not an extension: the name is cut at 255 and fails the extension check.
        assertThat(UploadCheck.cleanFileName("a".repeat(250) + "." + "b".repeat(50))).hasSize(255);
    }

    @Test
    void formatCharactersThatCanDisguiseAnExtensionAreRemoved() {
        // U+202E (right-to-left override) would display "statement\u202Etxt.exe" as "statementexe.txt".
        assertThat(UploadCheck.cleanFileName("statement\u202Etxt.exe")).isEqualTo("statementtxt.exe");
        assertThat(UploadCheck.cleanFileName("\uFEFFapril\u200B.csv")).isEqualTo("april.csv");
    }

    @ParameterizedTest
    @ValueSource(strings = {"april.ofx", "APRIL.QFX", "export.csv", "export.Txt", "a.b.ofx", "april.pdf", "April.PDF"})
    void allowedExtensions(String name) {
        assertThatCode(() -> UploadCheck.checkFileName(name)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"april.xlsx", "april", "april.ofx.exe", "april.pdf.exe", ".", ""})
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
        assertRejected(new byte[] {'P', 'K', 3, 4}, "ZIP");
        assertRejected(new byte[0], "empty");
    }

    @Test
    void pdfsAreJudgedByTheirFirstBytesOnly() {
        assertThat(UploadCheck.checkContent("%PDF-1.4\n%\u00e2\u00e3".getBytes(StandardCharsets.ISO_8859_1)))
                .isEqualTo(FileKind.PDF);
        // Binary after the header is the PDF extractor's concern, not the text checks'.
        assertThat(UploadCheck.checkContent(new byte[] {'%', 'P', 'D', 'F', '-', 0, 1, 2})).isEqualTo(FileKind.PDF);
        assertThat(UploadCheck.checkContent(" %PDF-1.4".getBytes(StandardCharsets.US_ASCII))).isEqualTo(FileKind.TEXT);
        assertThat(UploadCheck.checkContent("Date,Desc\n".getBytes(StandardCharsets.US_ASCII))).isEqualTo(FileKind.TEXT);
    }

    @Test
    void theExtensionMustAgreeWithTheKind() {
        assertThatCode(() -> UploadCheck.checkKindMatchesName("april.pdf", FileKind.PDF)).doesNotThrowAnyException();
        assertThatCode(() -> UploadCheck.checkKindMatchesName("APRIL.PDF", FileKind.PDF)).doesNotThrowAnyException();
        assertThatCode(() -> UploadCheck.checkKindMatchesName("april.csv", FileKind.TEXT)).doesNotThrowAnyException();
        for (String name : List.of("april.csv", "april.ofx", "april.txt")) {
            assertThatThrownBy(() -> UploadCheck.checkKindMatchesName(name, FileKind.PDF))
                    .isInstanceOfSatisfying(ImportRejectedException.class,
                            e -> assertThat(e.getProblem()).isEqualTo(ImportProblem.UNSUPPORTED_FORMAT))
                    .hasMessage("The file is a PDF; PDF statements must end in .pdf");
        }
        assertThatThrownBy(() -> UploadCheck.checkKindMatchesName("april.pdf", FileKind.TEXT))
                .isInstanceOfSatisfying(ImportRejectedException.class,
                        e -> assertThat(e.getProblem()).isEqualTo(ImportProblem.UNSUPPORTED_FORMAT))
                .hasMessage("The file ends in .pdf but is not a PDF");
    }

    @Test
    void webPagesAreUnsupported() {
        assertRejected("<!DOCTYPE html>\n<html>".getBytes(StandardCharsets.US_ASCII), "web page");
        assertRejected("\uFEFF \r\n<HTML lang=en>".getBytes(StandardCharsets.UTF_8), "web page");
        assertThatCode(() -> UploadCheck.checkContent("<?xml version=\"1.0\"?>\n<OFX>".getBytes(StandardCharsets.US_ASCII)))
                .doesNotThrowAnyException();
        assertThatCode(() -> UploadCheck.checkContent("<OFX>\n<html".getBytes(StandardCharsets.US_ASCII)))
                .doesNotThrowAnyException();
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

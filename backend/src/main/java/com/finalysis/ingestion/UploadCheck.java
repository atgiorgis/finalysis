package com.finalysis.ingestion;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Checks an uploaded statement before any parser sees it (SEC-10). The client's content type is
 * ignored; the file is judged by its name's extension and its bytes. Messages never quote file
 * contents.
 */
final class UploadCheck {

    /** Kept in step with spring.servlet.multipart.max-file-size, which rejects larger uploads first. */
    static final long MAX_BYTES = 10L * 1024 * 1024;
    static final Set<String> EXTENSIONS = Set.of("ofx", "qfx", "csv", "txt");
    static final int MAX_FILE_NAME = 255;

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private UploadCheck() {
    }

    static void checkSize(long size) {
        if (size > MAX_BYTES) {
            throw new ImportRejectedException(ImportProblem.PAYLOAD_TOO_LARGE, "Statement files are limited to 10 MB");
        }
    }

    /** The file name without any path, with control characters removed, at most 255 characters. */
    static String cleanFileName(String original) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.codePoints().filter(c -> !Character.isISOControl(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        return name.length() > MAX_FILE_NAME ? name.substring(0, MAX_FILE_NAME) : name;
    }

    static void checkFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension)) {
            throw unsupported("Statement files must end in .ofx, .qfx, .csv or .txt");
        }
    }

    /** Rejects empty files, PDFs, ZIP containers (including XLSX), binary data and invalid text. */
    static void checkContent(byte[] bytes) {
        if (bytes.length == 0) {
            throw unsupported("The file is empty");
        }
        if (startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            throw unsupported("PDF statements are not supported yet");
        }
        if (startsWith(bytes, new byte[] {'P', 'K', 3, 4})) {
            throw unsupported("ZIP and spreadsheet files are not supported; export the statement as OFX or CSV");
        }
        for (byte b : bytes) {
            // NUL and other C0 controls other than tab, LF, FF and CR don't occur in text statements.
            if (b >= 0 && b < 0x20 && b != '\t' && b != '\n' && b != '\f' && b != '\r') {
                throw unsupported("The file is not a text file");
            }
        }
        if (!decodes(bytes, StandardCharsets.UTF_8) && !decodes(bytes, WINDOWS_1252)) {
            throw unsupported("The file is not valid UTF-8 or windows-1252 text");
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean decodes(byte[] bytes, Charset charset) {
        try {
            charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static ImportRejectedException unsupported(String detail) {
        return new ImportRejectedException(ImportProblem.UNSUPPORTED_FORMAT, detail);
    }
}

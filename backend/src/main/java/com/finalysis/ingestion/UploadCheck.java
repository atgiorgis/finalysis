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
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private UploadCheck() {
    }

    static void checkSize(long size) {
        if (size > MAX_BYTES) {
            throw new ImportRejectedException(ImportProblem.PAYLOAD_TOO_LARGE, "Statement files are limited to 10 MB");
        }
    }

    /**
     * The file name without any path, with control and format characters (such as bidirectional
     * overrides, which can disguise an extension) removed, at most 255 characters. A long name
     * loses the end of its stem, not its extension, so the extension check still sees it.
     */
    static String cleanFileName(String original) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.codePoints().filter(c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        if (name.length() <= MAX_FILE_NAME) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot <= 16 ? name.substring(dot) : "";
        return truncate(name.substring(0, name.length() - extension.length()), MAX_FILE_NAME - extension.length())
                + extension;
    }

    /** At most {@code length} chars, never splitting a surrogate pair. */
    private static String truncate(String text, int length) {
        int end = Character.isHighSurrogate(text.charAt(length - 1)) ? length - 1 : length;
        return text.substring(0, end);
    }

    static void checkFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension)) {
            throw unsupported("Statement files must end in .ofx, .qfx, .csv or .txt");
        }
    }

    /** Rejects empty files, PDFs, ZIP containers (including XLSX), web pages, binary data and invalid text. */
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
        if (isHtml(bytes)) {
            throw unsupported("The file is a web page, not a statement export");
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

    /**
     * A saved web page (often a bank's sign-in or error page downloaded instead of the export).
     * Commas in its scripts or styles could otherwise pass for a delimited table. OFX in XML
     * starts with {@code <?xml}, {@code <?OFX} or {@code <OFX>}, never with these.
     */
    private static boolean isHtml(byte[] bytes) {
        int start = startsWith(bytes, UTF8_BOM) ? UTF8_BOM.length : 0;
        while (start < bytes.length && Character.isWhitespace(bytes[start])) {
            start++;
        }
        String head = new String(bytes, start, Math.min(bytes.length - start, 16), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        return head.startsWith("<!doctype html") || head.startsWith("<html");
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

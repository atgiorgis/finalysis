package com.finalysis.ingestion.pdf;

import com.finalysis.ingestion.pdf.boa.BankOfAmericaSkeletonLayout;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Prints the masked layout skeleton of one PDF to stdout (docs/plans/phase-3b.md §6). Plain Java:
 * no Spring, no database, no network. It reads the one file, writes no file, and logs nothing.
 *
 * <pre>
 * ./scripts/pdf-skeleton.sh /absolute/path/to/statement.pdf [--show-layout-words]
 * </pre>
 *
 * <p>Exit status: 0 when a skeleton was printed, 1 when the file can't be read or extracted, 2 for
 * a usage error. Messages never name the file or quote an exception.
 */
public final class PdfSkeletonCli {

    /** The upload limit ({@code spring.servlet.multipart.max-file-size}); larger files are refused unread. */
    static final long MAX_BYTES = 10L * 1024 * 1024;

    static final String SHOW_LAYOUT_WORDS = "--show-layout-words";

    static final String USAGE = "usage: pdf-skeleton <statement.pdf> [" + SHOW_LAYOUT_WORDS + "]";

    /** The layouts the tool knows: one per {@link PdfStatementParser}. */
    static final List<PdfSkeletonLayout> LAYOUTS = List.of(new BankOfAmericaSkeletonLayout());

    private PdfSkeletonCli() {
    }

    public static void main(String[] args) {
        silenceLogging();
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        int status = run(args, out);
        out.flush();
        System.exit(status);
    }

    /**
     * PDFBox logs through Commons Logging, which outside Spring would reach logback's default
     * console output at DEBUG, and PDFBox's glyph-level warnings quote text. So Commons Logging is
     * pointed at its no-op logger before any PDFBox class asks for one.
     */
    static void silenceLogging() {
        System.setProperty("org.apache.commons.logging.LogFactory", "org.apache.commons.logging.impl.LogFactoryImpl");
        System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");
    }

    static int run(String[] args, PrintStream out) {
        List<String> arguments = Arrays.asList(args);
        boolean showLayoutWords = arguments.contains(SHOW_LAYOUT_WORDS);
        List<String> paths = arguments.stream().filter(arg -> !arg.equals(SHOW_LAYOUT_WORDS)).toList();
        if (paths.size() != 1 || paths.getFirst().startsWith("--") || arguments.size() - paths.size() > 1) {
            out.println(USAGE);
            return 2;
        }

        byte[] bytes;
        try {
            Path path = Path.of(paths.getFirst());
            if (!Files.isRegularFile(path)) {
                out.println("read: FAILED (not a file)");
                return 1;
            }
            if (Files.size(path) > MAX_BYTES) {
                out.println("read: FAILED (larger than 10 MB, the upload limit)");
                return 1;
            }
            bytes = Files.readAllBytes(path);
        } catch (IOException | RuntimeException e) {
            out.println("read: FAILED (cannot read the file)");
            return 1;
        }
        if (!startsWithPdfHeader(bytes)) {
            out.println("read: FAILED (not a PDF: no %PDF- header)");
            return 1;
        }

        List<String> skeleton;
        try {
            skeleton = new PdfSkeleton(LAYOUTS).render(bytes, showLayoutWords);
        } catch (PdfExtractionException e) {
            out.println("extract: FAILED (" + PdfSkeleton.failure(e) + ")");
            return 1;
        }
        skeleton.forEach(out::println);
        return 0;
    }

    private static boolean startsWithPdfHeader(byte[] bytes) {
        byte[] header = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        return bytes.length >= header.length && Arrays.equals(bytes, 0, header.length, header, 0, header.length);
    }
}

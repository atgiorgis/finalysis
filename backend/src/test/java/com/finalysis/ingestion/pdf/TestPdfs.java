package com.finalysis.ingestion.pdf;

import com.finalysis.ingestion.ImportFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Builds synthetic PDFs in memory with PDFBox for extractor tests. Coordinates are given from the
 * page's top-left corner, like {@link PdfWord}, on a US Letter page.
 */
final class TestPdfs {

    static {
        // Before the fonts below exist: PDFBox keeps the first font mapper it is asked for, and its
        // default one scans system fonts and writes a cache file.
        NoSystemFontsMapper.install();
    }

    static final PDFont HELVETICA = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    static final PDFont HELVETICA_BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    static final float PAGE_HEIGHT = PDRectangle.LETTER.getHeight();

    private TestPdfs() {
    }

    /** Draws one page's content. */
    @FunctionalInterface
    interface PageDrawing {
        void draw(PDPageContentStream content) throws IOException;
    }

    /** One Letter page per drawing, content streams compressed as PDFBox does by default. */
    static byte[] pdf(PageDrawing... pages) {
        return pdf(true, pages);
    }

    static byte[] pdf(boolean compress, PageDrawing... pages) {
        try (PDDocument document = new PDDocument()) {
            for (PageDrawing drawing : pages) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page,
                        PDPageContentStream.AppendMode.OVERWRITE, compress)) {
                    drawing.draw(content);
                }
            }
            return save(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Shows {@code text} with its baseline at {@code baselineY} from the top. */
    static void text(PDPageContentStream content, PDFont font, float size, float x, float baselineY, String text)
            throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, PAGE_HEIGHT - baselineY);
        content.showText(text);
        content.endText();
    }

    static void text(PDPageContentStream content, float x, float baselineY, String text) throws IOException {
        text(content, HELVETICA, 10, x, baselineY, text);
    }

    /**
     * Saves without object streams, so decoded sizes in limit tests are exactly the streams a test
     * created. Content streams keep whatever filter they were built with.
     */
    static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out, CompressParameters.NO_COMPRESSION);
        return out.toByteArray();
    }

    /** A stream holding {@code bytes} without a filter. */
    static PDStream rawStream(PDDocument document, byte[] bytes) throws IOException {
        return new PDStream(document, new ByteArrayInputStream(bytes));
    }

    /**
     * A FlateDecode stream that inflates to {@code decodedBytes} zero bytes. The compressed bytes
     * are produced in chunks, so a gigabyte of output never exists in memory here either.
     */
    static PDStream flateBomb(PDDocument document, long decodedBytes) throws IOException {
        COSStream stream = document.getDocument().createCOSStream();
        try (OutputStream raw = stream.createRawOutputStream();
             OutputStream deflate = new DeflaterOutputStream(raw, new Deflater(Deflater.BEST_COMPRESSION), 64 * 1024)) {
            byte[] zeros = new byte[1024 * 1024];
            for (long written = 0; written < decodedBytes; written += zeros.length) {
                deflate.write(zeros, 0, (int) Math.min(zeros.length, decodedBytes - written));
            }
        }
        stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
        return new PDStream(stream);
    }

    static ImportFile file(byte[] bytes) {
        return ImportFile.of("statement.pdf", bytes);
    }

    static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}

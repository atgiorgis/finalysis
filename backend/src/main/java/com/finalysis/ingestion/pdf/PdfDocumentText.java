package com.finalysis.ingestion.pdf;

import java.util.List;

/**
 * The positioned text of a whole PDF, as {@link PdfTextExtractor} read it. Holds no PDFBox types,
 * so layout parsers never touch the library.
 *
 * @param pages          in document order
 * @param rotatedGlyphs  glyphs dropped because their text direction isn't horizontal (e.g. vertical
 *                       print codes in a margin)
 * @param unmappedGlyphs glyphs dropped because the font gives them no Unicode text
 */
public record PdfDocumentText(List<PdfPage> pages, int rotatedGlyphs, int unmappedGlyphs) {

    public PdfDocumentText {
        pages = List.copyOf(pages);
    }

    /** Every line of every page, in line-number order. */
    public List<PdfLine> lines() {
        return pages.stream().flatMap(page -> page.lines().stream()).toList();
    }

    /** Characters across all words; words never contain whitespace. */
    public int characterCount() {
        return lines().stream().flatMap(line -> line.words().stream()).mapToInt(word -> word.text().length()).sum();
    }

    /** Counts only (SEC-10). */
    @Override
    public String toString() {
        return "PdfDocumentText[" + pages.size() + " pages, " + lines().size() + " lines]";
    }
}

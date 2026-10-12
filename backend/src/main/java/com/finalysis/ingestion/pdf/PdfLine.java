package com.finalysis.ingestion.pdf;

import java.util.List;
import java.util.stream.Collectors;

/**
 * One line of extracted PDF text: words sharing a baseline, left to right.
 *
 * @param number 1-based and global across pages, top to bottom on each page in page order; every
 *               line counts, headers and footers included. Used as the source line number (DAT-07)
 * @param page   1-based page number
 * @param y      baseline of the line's first glyph (the topmost one), growing downwards
 * @param words  left to right, never empty
 */
public record PdfLine(int number, int page, float y, List<PdfWord> words) {

    public PdfLine {
        words = List.copyOf(words);
        if (words.isEmpty()) {
            throw new IllegalArgumentException("A line has at least one word");
        }
        if (number < 1 || page < 1) {
            throw new IllegalArgumentException("Line and page numbers start at 1");
        }
    }

    /**
     * The words joined by one space. Fingerprint-relevant: descriptions are built from this text
     * (docs/plans/phase-3b.md §1.3).
     */
    public String text() {
        return words.stream().map(PdfWord::text).collect(Collectors.joining(" "));
    }

    /** Position and counts only (SEC-10). */
    @Override
    public String toString() {
        return "PdfLine[" + number + ", page " + page + ", " + words.size() + " words]";
    }
}

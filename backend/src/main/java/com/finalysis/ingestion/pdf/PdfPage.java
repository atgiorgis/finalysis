package com.finalysis.ingestion.pdf;

import java.util.List;

/**
 * One page of extracted PDF text.
 *
 * @param number 1-based
 * @param width  crop box width in points, after page rotation
 * @param height crop box height in points, after page rotation
 * @param lines  top to bottom; empty for a page without text
 */
public record PdfPage(int number, float width, float height, List<PdfLine> lines) {

    public PdfPage {
        lines = List.copyOf(lines);
        if (number < 1) {
            throw new IllegalArgumentException("Page numbers start at 1");
        }
    }

    /** Size and counts only (SEC-10). */
    @Override
    public String toString() {
        return "PdfPage[" + number + ", " + lines.size() + " lines]";
    }
}

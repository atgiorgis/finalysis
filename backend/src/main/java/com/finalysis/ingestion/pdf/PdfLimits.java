package com.finalysis.ingestion.pdf;

import java.time.Duration;
import java.util.Objects;

/**
 * Limits on what {@link PdfTextExtractor} will process (SEC-10). Real checking statements run to a
 * few pages and well under 1 MB, so the defaults sit far above real files and far below harm.
 * Tests use smaller values to reach each limit cheaply.
 *
 * @param maxPages               pages per document
 * @param maxDecodedBytes        decoded bytes of all non-image streams together (content, forms,
 *                               fonts, object streams), and the heap cap of the stream cache
 * @param maxDecodedBytesPerPage decoded bytes of one page's content streams and the form XObjects
 *                               it can reach
 * @param maxGlyphsPerPage       glyphs shown on one page
 * @param maxGlyphsPerDocument   glyphs shown in the whole document
 * @param maxFormDepth           form XObjects nested inside each other
 * @param maxExtractionTime      wall-clock time for one extraction; checked cooperatively (per
 *                               stream, per page and every 1,000 glyphs), because PDFBox can't be
 *                               interrupted
 */
public record PdfLimits(int maxPages, long maxDecodedBytes, long maxDecodedBytesPerPage, int maxGlyphsPerPage,
                        int maxGlyphsPerDocument, int maxFormDepth, Duration maxExtractionTime) {

    /** The approved defaults (docs/plans/phase-3b.md §2.1). */
    public static final PdfLimits DEFAULT = new PdfLimits(50, 64L * 1024 * 1024, 8L * 1024 * 1024, 20_000, 200_000, 8,
            Duration.ofSeconds(10));

    public PdfLimits {
        Objects.requireNonNull(maxExtractionTime, "maxExtractionTime");
        if (maxPages < 1 || maxDecodedBytes < 1 || maxDecodedBytesPerPage < 1 || maxGlyphsPerPage < 1
                || maxGlyphsPerDocument < 1 || maxFormDepth < 0 || maxExtractionTime.isNegative()) {
            throw new IllegalArgumentException("PDF limits must be positive");
        }
    }
}

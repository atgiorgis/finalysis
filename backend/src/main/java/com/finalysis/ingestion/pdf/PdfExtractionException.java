package com.finalysis.ingestion.pdf;

import java.util.Locale;
import java.util.Objects;

/**
 * A PDF that {@link PdfTextExtractor} won't or can't read. The import maps each {@link Reason} to a
 * problem type (step 3 of docs/plans/phase-3b.md): {@code pdf-encrypted}, {@code pdf-too-complex},
 * or {@code parse-error}.
 *
 * <p>Messages are fixed text plus configured limit values. PDFBox's exception is deliberately not
 * kept as the cause: its messages can quote object contents, font names or text, and a logged
 * stack trace would print them (SEC-10).
 */
public final class PdfExtractionException extends RuntimeException {

    public enum Reason {
        /** The PDF needs a password to open. */
        PASSWORD_REQUIRED,
        /** The PDF exceeds one of the {@link PdfLimits}; {@link #limit()} says which. */
        TOO_COMPLEX,
        /** The bytes are not a PDF that PDFBox can read. */
        UNREADABLE
    }

    /** The {@link PdfLimits} component a {@link Reason#TOO_COMPLEX} PDF exceeded. */
    public enum Limit {
        PAGES, DECODED_BYTES, DECODED_BYTES_PER_PAGE, GLYPHS_PER_PAGE, GLYPHS_PER_DOCUMENT, FORM_DEPTH,
        EXTRACTION_TIME, NESTING
    }

    private final Reason reason;
    private final Limit limit;

    private PdfExtractionException(Reason reason, Limit limit, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.limit = limit;
    }

    static PdfExtractionException passwordRequired() {
        return new PdfExtractionException(Reason.PASSWORD_REQUIRED, null,
                "This PDF is password-protected. Download an unprotected copy from your bank, or open it and "
                        + "save it without a password.");
    }

    static PdfExtractionException unreadable() {
        return new PdfExtractionException(Reason.UNREADABLE, null, "The file is not a readable PDF");
    }

    static PdfExtractionException tooComplex(Limit limit, Object configured) {
        String what = switch (limit) {
            case PAGES -> "more than " + configured + " pages";
            case DECODED_BYTES -> "more than " + megabytes(configured) + " of decoded content";
            case DECODED_BYTES_PER_PAGE -> "a page with more than " + megabytes(configured) + " of decoded content";
            case GLYPHS_PER_PAGE -> "a page with more than " + configured + " characters";
            case GLYPHS_PER_DOCUMENT -> "more than " + configured + " characters";
            case FORM_DEPTH -> "form objects nested more than " + configured + " deep";
            case EXTRACTION_TIME -> "content that takes longer than " + configured + " to read";
            case NESTING -> "structures nested too deeply to read";
        };
        return new PdfExtractionException(Reason.TOO_COMPLEX, limit,
                "The PDF exceeds the processing limits: it has " + what);
    }

    private static String megabytes(Object bytes) {
        long value = ((Number) bytes).longValue();
        return value % (1024 * 1024) == 0 ? value / (1024 * 1024) + " MB" : value + " bytes";
    }

    public Reason reason() {
        return reason;
    }

    /** The exceeded limit for {@link Reason#TOO_COMPLEX}, else null. */
    public Limit limit() {
        return limit;
    }

    @Override
    public String toString() {
        return "PdfExtractionException[" + reason.name().toLowerCase(Locale.ROOT)
                + (limit == null ? "" : ", " + limit) + "]";
    }
}

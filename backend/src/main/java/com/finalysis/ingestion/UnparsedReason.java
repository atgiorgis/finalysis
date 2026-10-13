package com.finalysis.ingestion;

import com.finalysis.ingestion.pdf.PdfDocumentText;

/** Why an uploaded PDF was recorded as an {@link UnparsedUpload} instead of imported (ING-13). */
public enum UnparsedReason {

    /** The PDF has text, but no layout parser claims it. */
    UNRECOGNIZED_LAYOUT,

    /** Fewer than {@value #MIN_TEXT_CHARACTERS} non-whitespace characters: almost certainly a scan (OCR, Phase 8). */
    NO_TEXT_LAYER;

    static final int MIN_TEXT_CHARACTERS = 20;

    /** The reason for a PDF no parser claimed. */
    public static UnparsedReason of(PdfDocumentText text) {
        return text.characterCount() < MIN_TEXT_CHARACTERS ? NO_TEXT_LAYER : UNRECOGNIZED_LAYOUT;
    }
}

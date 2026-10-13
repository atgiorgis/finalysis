package com.finalysis.ingestion;

/**
 * What an upload's bytes are, as judged by {@link UploadCheck#checkContent}. The import routes by
 * kind: PDFs are extracted once and offered only to PDF parsers, text files only to the others
 * (docs/plans/phase-3b.md §1.6).
 */
public enum FileKind {

    /** Starts with {@code %PDF-} at offset 0. */
    PDF,

    /** Text in UTF-8 or windows-1252 (OFX, QFX, CSV). */
    TEXT
}

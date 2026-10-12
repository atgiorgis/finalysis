package com.finalysis.ingestion;

/** How confidently a {@link StatementParser} recognizes a file, judged by its content. */
public enum FormatMatch {

    /** Not this parser's format. */
    NONE,

    /**
     * Could be read by this generic parser (e.g. any delimited text as CSV), but only if no parser
     * recognizes the file definitely.
     */
    FALLBACK,

    /** Unmistakably this parser's format (e.g. an OFX header). */
    DEFINITE
}

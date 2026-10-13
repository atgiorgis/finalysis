package com.finalysis.ingestion.pdf;

import com.finalysis.ingestion.FormatMatch;
import com.finalysis.ingestion.ImportFile;
import com.finalysis.ingestion.ParseContext;
import com.finalysis.ingestion.ParsedStatement;
import com.finalysis.ingestion.StatementParser;

/**
 * Reads one PDF statement layout (ING-12): one implementation per layout, and a new layout is a
 * new implementation (GR-05). Layout parsers work on text the import has already extracted, so
 * the extraction limits and the encryption check run once, before any layout code
 * (docs/plans/phase-3b.md §1.4). The registry offers PDFs only to these parsers, and these
 * parsers only PDFs.
 *
 * <p>Bank-specific knowledge lives only inside implementations.
 */
public interface PdfStatementParser extends StatementParser {

    /**
     * {@link FormatMatch#DEFINITE} when the layout's markers are all present, else
     * {@link FormatMatch#NONE}. PDF layouts have no fallback level: anything else counts as NONE.
     */
    FormatMatch detect(PdfDocumentText text);

    /**
     * Reads the whole statement. Descriptions are passed out exactly as extracted.
     *
     * @throws com.finalysis.ingestion.StatementParseException if the layout is recognized but a
     *                                                         line can't be read
     */
    ParsedStatement parse(PdfDocumentText text, ParseContext context);

    /** Extracts with the default limits and delegates; a file that can't be extracted is NONE. */
    @Override
    default FormatMatch detect(ImportFile file) {
        try {
            return detect(new PdfTextExtractor().extract(file));
        } catch (PdfExtractionException e) {
            return FormatMatch.NONE;
        }
    }

    /**
     * Extracts with the default limits and delegates. The import doesn't use this: it extracts
     * once and calls {@link #parse(PdfDocumentText, ParseContext)}.
     *
     * @throws PdfExtractionException if the file can't be extracted
     */
    @Override
    default ParsedStatement parse(ImportFile file, ParseContext context) {
        return parse(new PdfTextExtractor().extract(file), context);
    }
}

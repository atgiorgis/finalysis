package com.finalysis.ingestion.pdf;

import java.util.List;
import java.util.SequencedMap;
import java.util.Set;

/**
 * What the skeleton debug tool ({@link PdfSkeleton}) needs from one PDF layout: its detection
 * markers, its line classification, its fixed vocabulary and its open guesses. Implemented next to
 * the layout's {@link PdfStatementParser}, from the same code the parser uses, so the tool shows
 * exactly what the parser sees. Bank-specific knowledge stays in the implementation.
 *
 * <p>Implementations return names and kinds only, never statement text: the tool masks the text
 * itself.
 */
public interface PdfSkeletonLayout {

    /** The id of the layout's parser. */
    String parserId();

    /**
     * Each detection marker by short name, in the order the layout looks for them; the parser's
     * detection is DEFINITE exactly when all are true.
     */
    SequencedMap<String, Boolean> markers(PdfDocumentText text);

    /** Every kind {@link #classify} can return, in the order the summary lists them. */
    List<String> kinds();

    /** One kind per line of {@code text}, in line-number order. */
    List<String> classify(PdfDocumentText text);

    /** Words of the layout's fixed text, which the tool may show unmasked on request. */
    Set<String> layoutWords();

    /** The layout guesses a real statement's skeleton has to confirm, by name. */
    List<String> openGuesses();

    /** What reading the statement gives, as a fixed status that never quotes statement text. */
    String parseStatus(PdfDocumentText text);
}

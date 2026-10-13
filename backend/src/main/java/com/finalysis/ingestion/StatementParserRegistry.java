package com.finalysis.ingestion;

import com.finalysis.ingestion.pdf.PdfDocumentText;
import com.finalysis.ingestion.pdf.PdfStatementParser;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Picks the parser for an uploaded file. Parsers are split by the kind of file they read, so a
 * PDF is never offered to a text parser (an uncompressed PDF is ASCII with commas and could pass
 * for CSV) and a text file never to a PDF layout parser (docs/plans/phase-3b.md §1.6).
 *
 * <p>A text file goes to {@link #select}, which asks every non-PDF {@link StatementParser} to
 * {@linkplain StatementParser#detect detect} it:
 * <ol>
 *   <li>exactly one {@link FormatMatch#DEFINITE} match wins, whatever the fallbacks say;</li>
 *   <li>with no definite match, exactly one {@link FormatMatch#FALLBACK} match wins;</li>
 *   <li>no match at all is an {@link UnsupportedFormatException};</li>
 *   <li>several matches at the deciding level are an {@link AmbiguousFormatException}. That is a
 *       bug in the parsers' detection, not in the file, so it is reported rather than guessed.</li>
 * </ol>
 * An extracted PDF goes to {@link #selectPdf}.
 */
@Component
public class StatementParserRegistry {

    private final List<StatementParser> textParsers;
    private final List<PdfStatementParser> pdfParsers;

    public StatementParserRegistry(List<StatementParser> parsers) {
        Set<String> ids = new HashSet<>();
        for (StatementParser parser : parsers) {
            if (!ids.add(parser.id())) {
                throw new IllegalStateException("Two statement parsers share the id " + parser.id());
            }
        }
        this.textParsers = parsers.stream().filter(parser -> !(parser instanceof PdfStatementParser)).toList();
        this.pdfParsers = parsers.stream().filter(PdfStatementParser.class::isInstance)
                .map(PdfStatementParser.class::cast).toList();
    }

    /** The parser for a text file, asking only non-PDF parsers. */
    public StatementParser select(ImportFile file) {
        List<StatementParser> definite = matching(file, FormatMatch.DEFINITE);
        if (!definite.isEmpty()) {
            return single(definite);
        }
        List<StatementParser> fallback = matching(file, FormatMatch.FALLBACK);
        if (!fallback.isEmpty()) {
            return single(fallback);
        }
        throw new UnsupportedFormatException();
    }

    /**
     * The layout parser for an extracted PDF, asking only {@link PdfStatementParser}s. PDF layouts
     * have no fallback level: exactly one {@link FormatMatch#DEFINITE} match wins, none is empty
     * (the import records an unparsed upload, ING-13), several are an
     * {@link AmbiguousFormatException}.
     */
    public Optional<PdfStatementParser> selectPdf(PdfDocumentText text) {
        List<PdfStatementParser> definite = pdfParsers.stream()
                .filter(parser -> parser.detect(text) == FormatMatch.DEFINITE).toList();
        return definite.isEmpty() ? Optional.empty() : Optional.of(single(definite));
    }

    private List<StatementParser> matching(ImportFile file, FormatMatch level) {
        return textParsers.stream().filter(parser -> parser.detect(file) == level).toList();
    }

    private static <P extends StatementParser> P single(List<P> candidates) {
        if (candidates.size() > 1) {
            throw new AmbiguousFormatException(candidates.stream().map(StatementParser::id).sorted().toList());
        }
        return candidates.getFirst();
    }

    /** No parser recognizes the file. */
    public static class UnsupportedFormatException extends RuntimeException {

        UnsupportedFormatException() {
            super("No statement parser recognizes this file");
        }
    }

    /** More than one parser claims the file at the same level. */
    public static class AmbiguousFormatException extends RuntimeException {

        private final List<String> parserIds;

        AmbiguousFormatException(List<String> parserIds) {
            super("More than one statement parser recognizes this file: " + String.join(", ", parserIds));
            this.parserIds = List.copyOf(parserIds);
        }

        public List<String> getParserIds() {
            return parserIds;
        }
    }
}

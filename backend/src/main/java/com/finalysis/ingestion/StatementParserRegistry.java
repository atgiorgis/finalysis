package com.finalysis.ingestion;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Picks the parser for an uploaded file by asking every {@link StatementParser} to
 * {@linkplain StatementParser#detect detect} it:
 * <ol>
 *   <li>exactly one {@link FormatMatch#DEFINITE} match wins, whatever the fallbacks say;</li>
 *   <li>with no definite match, exactly one {@link FormatMatch#FALLBACK} match wins;</li>
 *   <li>no match at all is an {@link UnsupportedFormatException};</li>
 *   <li>several matches at the deciding level are an {@link AmbiguousFormatException}. That is a
 *       bug in the parsers' detection, not in the file, so it is reported rather than guessed.</li>
 * </ol>
 */
@Component
public class StatementParserRegistry {

    private final List<StatementParser> parsers;

    public StatementParserRegistry(List<StatementParser> parsers) {
        Set<String> ids = new HashSet<>();
        for (StatementParser parser : parsers) {
            if (!ids.add(parser.id())) {
                throw new IllegalStateException("Two statement parsers share the id " + parser.id());
            }
        }
        this.parsers = List.copyOf(parsers);
    }

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

    private List<StatementParser> matching(ImportFile file, FormatMatch level) {
        return parsers.stream().filter(parser -> parser.detect(file) == level).toList();
    }

    private static StatementParser single(List<StatementParser> candidates) {
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

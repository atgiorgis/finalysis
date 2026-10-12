package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.ingestion.StatementParserRegistry.AmbiguousFormatException;
import com.finalysis.ingestion.StatementParserRegistry.UnsupportedFormatException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatementParserRegistryTest {

    private static final ImportFile FILE = ImportFile.of("statement.txt", "content".getBytes(StandardCharsets.UTF_8));

    @Test
    void noMatchIsUnsupported() {
        StatementParserRegistry registry = new StatementParserRegistry(List.of(
                parser("ofx", FormatMatch.NONE), parser("csv", FormatMatch.NONE)));

        assertThatThrownBy(() -> registry.select(FILE)).isInstanceOf(UnsupportedFormatException.class);
    }

    @Test
    void noParsersAtAllIsUnsupported() {
        assertThatThrownBy(() -> new StatementParserRegistry(List.of()).select(FILE))
                .isInstanceOf(UnsupportedFormatException.class);
    }

    @Test
    void singleDefiniteMatchIsSelected() {
        StatementParser ofx = parser("ofx", FormatMatch.DEFINITE);
        StatementParserRegistry registry = new StatementParserRegistry(List.of(parser("pdf", FormatMatch.NONE), ofx));

        assertThat(registry.select(FILE)).isSameAs(ofx);
    }

    @Test
    void definiteMatchBeatsFallback() {
        StatementParser ofx = parser("ofx", FormatMatch.DEFINITE);
        StatementParserRegistry registry = new StatementParserRegistry(List.of(parser("csv", FormatMatch.FALLBACK), ofx));

        assertThat(registry.select(FILE)).isSameAs(ofx);
    }

    @Test
    void fallbackIsSelectedWhenNothingMatchesDefinitely() {
        StatementParser csv = parser("csv", FormatMatch.FALLBACK);
        StatementParserRegistry registry = new StatementParserRegistry(List.of(parser("ofx", FormatMatch.NONE), csv));

        assertThat(registry.select(FILE)).isSameAs(csv);
    }

    @Test
    void twoDefiniteMatchesAreAmbiguousEvenWithAFallback() {
        StatementParserRegistry registry = new StatementParserRegistry(List.of(
                parser("pdf-b", FormatMatch.DEFINITE), parser("csv", FormatMatch.FALLBACK), parser("pdf-a", FormatMatch.DEFINITE)));

        assertThatThrownBy(() -> registry.select(FILE))
                .isInstanceOfSatisfying(AmbiguousFormatException.class,
                        e -> assertThat(e.getParserIds()).containsExactly("pdf-a", "pdf-b"));
    }

    @Test
    void twoFallbackMatchesAreAmbiguous() {
        StatementParserRegistry registry = new StatementParserRegistry(List.of(
                parser("csv", FormatMatch.FALLBACK), parser("tsv", FormatMatch.FALLBACK)));

        assertThatThrownBy(() -> registry.select(FILE)).isInstanceOf(AmbiguousFormatException.class);
    }

    @Test
    void duplicateParserIdsFailAtStartup() {
        assertThatThrownBy(() -> new StatementParserRegistry(List.of(
                parser("ofx", FormatMatch.NONE), parser("ofx", FormatMatch.NONE))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ofx");
    }

    @Test
    void importFileHashesContentAndPrintsNoFileName() {
        ImportFile file = ImportFile.of("Jane Doe checking.csv", "abc".getBytes(StandardCharsets.UTF_8));

        assertThat(file.sha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(file.toString()).doesNotContain("Jane").contains("3 bytes");
    }

    private static StatementParser parser(String id, FormatMatch match) {
        return new StatementParser() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public FormatMatch detect(ImportFile file) {
                return match;
            }

            @Override
            public ParsedStatement parse(ImportFile file, ParseContext context) {
                throw new UnsupportedOperationException();
            }
        };
    }
}

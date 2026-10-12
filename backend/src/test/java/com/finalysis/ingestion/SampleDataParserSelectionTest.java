package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.ingestion.csv.CsvStatementParser;
import com.finalysis.ingestion.ofx.OfxStatementParser;
import com.finalysis.support.ImportTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every file under {@code sample-data/}, broken and edge cases included, is claimed by exactly one
 * parser: OFX/QFX files by the OFX parser (a definite match beats the CSV fallback), everything
 * else by the CSV parser. Detection is by content only; the extension just names the expectation.
 */
class SampleDataParserSelectionTest {

    private final StatementParserRegistry registry =
            new StatementParserRegistry(List.of(new OfxStatementParser(), new CsvStatementParser()));

    static Stream<Path> sampleFiles() throws IOException {
        try (Stream<Path> files = Files.walk(ImportTestSupport.SAMPLE_DATA)) {
            return files.filter(Files::isRegularFile)
                    .map(ImportTestSupport.SAMPLE_DATA::relativize)
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @Test
    void sampleDataHasEveryKindOfFile() throws IOException {
        List<String> names = sampleFiles().map(Path::toString).toList();

        assertThat(names).anyMatch(name -> name.startsWith("broken/"))
                .anyMatch(name -> name.startsWith("edge-cases/csv-"))
                .anyMatch(name -> name.startsWith("edge-cases/ofx-"))
                .anyMatch(name -> name.endsWith(".qfx"))
                // 25 CSV statements, 1 broken, 13 OFX/QFX, 4 OFX and 7 CSV edge cases, manifest, answer key
                .hasSize(52);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sampleFiles")
    void everySampleFileResolvesToExactlyOneParser(Path relativePath) throws IOException {
        ImportFile file = ImportFile.of(relativePath.getFileName().toString(),
                Files.readAllBytes(ImportTestSupport.SAMPLE_DATA.resolve(relativePath)));
        String name = relativePath.toString();
        String expected = name.endsWith(".ofx") || name.endsWith(".qfx") ? "ofx" : "csv";

        assertThat(registry.select(file).id()).isEqualTo(expected);
    }
}

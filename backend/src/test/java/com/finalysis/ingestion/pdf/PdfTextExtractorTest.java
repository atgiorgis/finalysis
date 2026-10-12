package com.finalysis.ingestion.pdf;

import static com.finalysis.ingestion.pdf.TestPdfs.HELVETICA;
import static com.finalysis.ingestion.pdf.TestPdfs.HELVETICA_BOLD;
import static com.finalysis.ingestion.pdf.TestPdfs.PAGE_HEIGHT;
import static com.finalysis.ingestion.pdf.TestPdfs.file;
import static com.finalysis.ingestion.pdf.TestPdfs.pdf;
import static com.finalysis.ingestion.pdf.TestPdfs.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The line and word rules of {@link PdfTextExtractor}. They decide the text that descriptions, and
 * so fingerprints, are built from, so each rule is pinned here (docs/plans/phase-3b.md §1.3).
 */
class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    private PdfDocumentText extract(byte[] pdf) {
        return extractor.extract(file(pdf));
    }

    private List<String> lineTexts(byte[] pdf) {
        return extract(pdf).lines().stream().map(PdfLine::text).toList();
    }

    @Nested
    class Positions {

        @Test
        void wordsCarryPositionsFromTheTopLeftCorner() {
            PdfDocumentText text = extract(pdf(content -> text(content, 72, 100, "Opening balance")));

            PdfLine line = text.lines().getFirst();
            assertThat(line.text()).isEqualTo("Opening balance");
            assertThat(line.number()).isEqualTo(1);
            assertThat(line.page()).isEqualTo(1);
            assertThat(line.y()).isCloseTo(100f, within(0.01f));
            PdfWord first = line.words().getFirst();
            assertThat(first.text()).isEqualTo("Opening");
            assertThat(first.x()).isCloseTo(72f, within(0.01f));
            assertThat(first.xEnd()).isCloseTo(72f + HELVETICA_WIDTH_OPENING, within(0.01f));
            assertThat(first.baselineY()).isCloseTo(100f, within(0.01f));
            assertThat(first.fontSize()).isEqualTo(10f);
            assertThat(line.words().get(1).x()).isGreaterThan(first.xEnd());
        }

        @Test
        void pageSizeComesFromTheCropBox() {
            PdfPage page = extract(pdf(content -> text(content, 72, 100, "x"))).pages().getFirst();

            assertThat(page.number()).isEqualTo(1);
            assertThat(page.width()).isEqualTo(PDRectangle.LETTER.getWidth());
            assertThat(page.height()).isEqualTo(PDRectangle.LETTER.getHeight());
        }

        @Test
        void aRotatedPageSwapsWidthAndHeight() throws IOException {
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                page.setRotation(90);
                document.addPage(page);

                PdfPage extracted = extract(TestPdfs.save(document)).pages().getFirst();

                assertThat(extracted.width()).isEqualTo(PDRectangle.LETTER.getHeight());
                assertThat(extracted.height()).isEqualTo(PDRectangle.LETTER.getWidth());
                assertThat(extracted.lines()).isEmpty();
            }
        }

        @Test
        void boldComesFromTheFont() {
            PdfLine line = extract(pdf(content -> {
                text(content, HELVETICA_BOLD, 10, 72, 100, "Deposits");
                text(content, HELVETICA, 10, 200, 100, "plain");
            })).lines().getFirst();

            assertThat(line.words()).extracting(PdfWord::bold).containsExactly(true, false);
        }
    }

    @Nested
    class Lines {

        @Test
        void linesRunTopToBottomWhateverTheDrawingOrder() {
            assertThat(lineTexts(pdf(content -> {
                text(content, 72, 300, "third");
                text(content, 72, 100, "first");
                text(content, 72, 200, "second");
            }))).containsExactly("first", "second", "third");
        }

        @Test
        void baselinesWithinAQuarterOfTheFontSizeShareALine() {
            // Font size 10: tolerance 2.5 pt from the line's topmost glyph.
            assertThat(lineTexts(pdf(content -> {
                text(content, 72, 100, "date");
                text(content, 200, 102, "description");
                text(content, 72, 200, "next");
                text(content, 200, 203, "apart");
            }))).containsExactly("date description", "next", "apart");
        }

        @Test
        void lineYIsTheTopmostGlyphsBaseline() {
            PdfLine line = extract(pdf(content -> {
                text(content, 200, 101.5f, "lower");
                text(content, 72, 100, "upper");
            })).lines().getFirst();

            assertThat(line.y()).isCloseTo(100f, within(0.01f));
            assertThat(line.text()).isEqualTo("upper lower");
        }

        @Test
        void lineNumbersAreGlobalAcrossPages() {
            PdfDocumentText text = extract(pdf(
                    content -> {
                        text(content, 72, 100, "one");
                        text(content, 72, 120, "two");
                    },
                    content -> text(content, 72, 100, "three")));

            assertThat(text.lines()).extracting(PdfLine::number).containsExactly(1, 2, 3);
            assertThat(text.lines()).extracting(PdfLine::page).containsExactly(1, 1, 2);
            assertThat(text.pages()).extracting(page -> page.lines().size()).containsExactly(2, 1);
        }

        @Test
        void aPageWithoutTextHasNoLines() {
            PdfDocumentText text = extract(pdf(content -> { }, content -> text(content, 72, 100, "after")));

            assertThat(text.pages().getFirst().lines()).isEmpty();
            assertThat(text.lines()).extracting(PdfLine::number).containsExactly(1);
            assertThat(text.characterCount()).isEqualTo(5);
        }
    }

    @Nested
    class Words {

        @Test
        void wordsRunLeftToRightWhateverTheDrawingOrder() {
            assertThat(lineTexts(pdf(content -> {
                text(content, 500, 100, "-45.67");
                text(content, 72, 100, "07/02/26");
                text(content, 140, 100, "COFFEE SHOP");
            }))).containsExactly("07/02/26 COFFEE SHOP -45.67");
        }

        @Test
        void spacesEndWordsAndRunsOfSpacesCollapse() {
            PdfLine line = extract(pdf(content -> text(content, 72, 100, "ACME    CO  STORE"))).lines().getFirst();

            assertThat(line.words()).extracting(PdfWord::text).containsExactly("ACME", "CO", "STORE");
            assertThat(line.text()).isEqualTo("ACME CO STORE");
        }

        @Test
        void aGapOfThreeTenthsOfTheFontSizeEndsAWord() {
            // TJ offsets are thousandths of the font size: at 10 pt, -290 is a 2.9 pt gap and -310 a
            // 3.1 pt gap, either side of the 3 pt threshold.
            assertThat(lineTexts(pdf(content -> {
                kerned(content, 100, "AMAZON", -290f, "MKTPLACE");
                kerned(content, 200, "AMAZON", -310f, "MKTPLACE");
            }))).containsExactly("AMAZONMKTPLACE", "AMAZON MKTPLACE");
        }

        @Test
        void tightKerningKeepsAWordTogether() {
            assertThat(lineTexts(pdf(content -> kerned(content, 100, "PAY", 50f, "ROLL")))).containsExactly("PAYROLL");
        }

        @Test
        void aColumnGapSplitsWordsWithoutAnySpaceGlyph() {
            PdfLine line = extract(pdf(content -> {
                text(content, 72, 100, "Total");
                text(content, 480, 100, "$1,234.56");
            })).lines().getFirst();

            assertThat(line.words()).extracting(PdfWord::text).containsExactly("Total", "$1,234.56");
            assertThat(line.words().get(1).x()).isCloseTo(480f, within(0.01f));
        }

        @Test
        void aGlyphDrawnTwiceAtTheSamePlaceIsKeptOnce() {
            // Fake bold: the same text drawn again 0.3 pt to the right.
            assertThat(lineTexts(pdf(content -> {
                text(content, 72, 100, "Checks");
                text(content, 72.3f, 100.2f, "Checks");
            }))).containsExactly("Checks");
        }

        @Test
        void aRepeatedCharacterFurtherAwayIsKept() {
            assertThat(lineTexts(pdf(content -> {
                text(content, 72, 100, "1");
                text(content, 72.6f, 100, "1");
            }))).containsExactly("11");
        }

        @Test
        void glyphsPlacedOneByOneFormTheSameWords() {
            byte[] whole = pdf(content -> text(content, 72, 100, "Service fees"));
            byte[] oneByOne = pdf(content -> {
                float x = 72;
                for (char c : "Service fees".toCharArray()) {
                    String glyph = String.valueOf(c);
                    text(content, x, 100, glyph);
                    x += HELVETICA.getStringWidth(glyph) / 1000 * 10;
                }
            });

            assertThat(lineTexts(oneByOne)).isEqualTo(lineTexts(whole)).containsExactly("Service fees");
        }

        @Test
        void compressedAndUncompressedContentGiveTheSameText() {
            TestPdfs.PageDrawing drawing = content -> {
                text(content, 72, 100, "Deposits and other additions");
                text(content, 72, 120, "07/03/26 PAYROLL 2,451.08");
            };

            assertThat(extract(pdf(false, drawing)).lines()).usingRecursiveFieldByFieldElementComparator()
                    .isEqualTo(extract(pdf(true, drawing)).lines());
        }
    }

    @Nested
    class DroppedGlyphs {

        @Test
        void rotatedTextIsDroppedAndCounted() {
            PdfDocumentText text = extract(pdf(content -> {
                content.beginText();
                content.setFont(HELVETICA, 6);
                content.setTextMatrix(Matrix.getRotateInstance(Math.PI / 2, 20, 300));
                content.showText("0012345");
                content.endText();
                text(content, 72, 100, "kept");
            }));

            assertThat(text.lines()).extracting(PdfLine::text).containsExactly("kept");
            assertThat(text.rotatedGlyphs()).isEqualTo(7);
            assertThat(text.unmappedGlyphs()).isZero();
        }
    }

    @Nested
    class NoContentInToString {

        @Test
        void recordsPrintPositionsAndCountsOnly() {
            PdfDocumentText text = extract(pdf(content -> text(content, 72, 100, "SECRETMARKER 4111")));
            PdfLine line = text.lines().getFirst();

            assertThat(List.of(text.toString(), text.pages().getFirst().toString(), line.toString(),
                    line.words().getFirst().toString()))
                    .noneMatch(s -> s.contains("SECRETMARKER") || s.contains("4111"));
        }
    }

    private static final float HELVETICA_WIDTH_OPENING = width("Opening");

    private static float width(String text) {
        try {
            return HELVETICA.getStringWidth(text) / 1000 * 10;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Two strings in one TJ array with a positioning offset between them, at 10 pt. */
    private static void kerned(PDPageContentStream content, float baselineY, String left, float offset, String right)
            throws IOException {
        content.beginText();
        content.setFont(HELVETICA, 10);
        content.newLineAtOffset(72, PAGE_HEIGHT - baselineY);
        content.showTextWithPositioning(new Object[] {left, offset, right});
        content.endText();
    }
}

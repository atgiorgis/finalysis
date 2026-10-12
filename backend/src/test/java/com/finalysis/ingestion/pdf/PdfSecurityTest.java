package com.finalysis.ingestion.pdf;

import static com.finalysis.ingestion.pdf.TestPdfs.ascii;
import static com.finalysis.ingestion.pdf.TestPdfs.file;
import static com.finalysis.ingestion.pdf.TestPdfs.pdf;
import static com.finalysis.ingestion.pdf.TestPdfs.rawStream;
import static com.finalysis.ingestion.pdf.TestPdfs.save;
import static com.finalysis.ingestion.pdf.TestPdfs.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.ingestion.pdf.PdfExtractionException.Limit;
import com.finalysis.ingestion.pdf.PdfExtractionException.Reason;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * PDF hardening of {@link PdfTextExtractor} (SEC-10): each limit at and just past its boundary,
 * encryption, inert content, hostile and broken files, and no disk writes. Every PDF is built in
 * memory; none is committed.
 */
class PdfSecurityTest {

    private static final String MARKER = "LEAKMARKER";

    private static PdfLimits limits(int maxPages, long maxDecoded, long maxDecodedPerPage, int maxGlyphsPerPage,
                                    int maxGlyphsPerDocument, int maxFormDepth) {
        return new PdfLimits(maxPages, maxDecoded, maxDecodedPerPage, maxGlyphsPerPage, maxGlyphsPerDocument,
                maxFormDepth, Duration.ofSeconds(10));
    }

    private static PdfDocumentText extract(byte[] pdf) {
        return new PdfTextExtractor().extract(file(pdf));
    }

    private static PdfDocumentText extract(PdfLimits limits, byte[] pdf) {
        return new PdfTextExtractor(limits).extract(file(pdf));
    }

    private static void assertTooComplex(Runnable extraction, Limit limit) {
        assertThatThrownBy(extraction::run)
                .isInstanceOfSatisfying(PdfExtractionException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.TOO_COMPLEX);
                    assertThat(e.limit()).isEqualTo(limit);
                    assertThat(e.getCause()).isNull();
                });
    }

    private static String allText(PdfDocumentText text) {
        return text.lines().stream().map(PdfLine::text).collect(Collectors.joining("\n"));
    }

    @Nested
    class Pages {

        @Test
        void fiftyPagesAreRead() {
            assertThat(extract(blankPages(50)).pages()).hasSize(50);
        }

        @Test
        void fiftyOnePagesAreTooMany() {
            assertTooComplex(() -> extract(blankPages(51)), Limit.PAGES);
        }

        private static byte[] blankPages(int count) {
            try (PDDocument document = new PDDocument()) {
                for (int i = 0; i < count; i++) {
                    document.addPage(new PDPage(PDRectangle.LETTER));
                }
                return save(document);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Nested
    class DecodedSize {

        @Test
        void aPageAtTheLimitIsRead() {
            assertThat(extract(limits(50, 10_000, 1000, 100, 100, 8), pageWithContentOf(1000)).pages()).hasSize(1);
        }

        @Test
        void aPageOneByteOverTheLimitIsRefused() {
            assertTooComplex(() -> extract(limits(50, 10_000, 1000, 100, 100, 8), pageWithContentOf(1001)),
                    Limit.DECODED_BYTES_PER_PAGE);
        }

        @Test
        void formsAPageDrawsCountTowardsItsLimit() throws IOException {
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                PDFormXObject form = new PDFormXObject(rawStream(document, padded("", 600)));
                form.setBBox(PDRectangle.LETTER);
                page.setResources(new PDResources());
                COSName name = page.getResources().add(form);
                page.setContents(rawStream(document, padded("/" + name.getName() + " Do", 401)));
                byte[] bytes = save(document);

                assertThat(extract(limits(50, 10_000, 1001, 100, 100, 8), bytes).pages()).hasSize(1);
                assertTooComplex(() -> extract(limits(50, 10_000, 1000, 100, 100, 8), bytes),
                        Limit.DECODED_BYTES_PER_PAGE);
            }
        }

        @Test
        void theWholeDocumentHasItsOwnLimit() throws IOException {
            try (PDDocument document = new PDDocument()) {
                for (int i = 0; i < 2; i++) {
                    PDPage page = new PDPage(PDRectangle.LETTER);
                    document.addPage(page);
                    page.setContents(rawStream(document, padded("", 600)));
                }
                byte[] bytes = save(document);

                assertThat(extract(limits(50, 1200, 1000, 100, 100, 8), bytes).pages()).hasSize(2);
                assertTooComplex(() -> extract(limits(50, 1199, 1000, 100, 100, 8), bytes), Limit.DECODED_BYTES);
            }
        }

        @Test
        void objectStreamsCountTowardsTheDocumentLimit() throws IOException {
            try (PDDocument document = new PDDocument()) {
                document.addPage(new PDPage(PDRectangle.LETTER));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                // PDFBox's default save packs the catalog and page objects into a compressed object stream.
                document.save(out, CompressParameters.DEFAULT_COMPRESSION);

                assertTooComplex(() -> extract(limits(50, 10, 1000, 100, 100, 8), out.toByteArray()),
                        Limit.DECODED_BYTES);
            }
        }

        @Test
        @Timeout(30)
        void aFlateBombInPageContentFailsFast() throws IOException {
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                page.setContents(TestPdfs.flateBomb(document, 1024L * 1024 * 1024));
                byte[] bytes = save(document);
                assertThat(bytes.length).as("compressed size").isLessThan(2 * 1024 * 1024);

                long start = System.nanoTime();
                assertTooComplex(() -> extract(bytes), Limit.DECODED_BYTES);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            }
        }

        @Test
        void aFlateBombAnywhereInTheDocumentIsFound() throws IOException {
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                // Reachable from the catalog, so it is saved, but never drawn.
                document.getDocumentCatalog().getCOSObject()
                        .setItem(COSName.getPDFName("Junk"), TestPdfs.flateBomb(document, 100L * 1024 * 1024));
                byte[] bytes = save(document);

                assertTooComplex(() -> extract(bytes), Limit.DECODED_BYTES);
            }
        }

        private static byte[] pageWithContentOf(int bytes) {
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                page.setContents(rawStream(document, padded("", bytes)));
                return save(document);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** Content-stream text padded with spaces (valid content whitespace) to exactly {@code length} bytes. */
        private static byte[] padded(String operators, int length) {
            byte[] bytes = new byte[length];
            Arrays.fill(bytes, (byte) ' ');
            byte[] start = ascii(operators);
            System.arraycopy(start, 0, bytes, 0, start.length);
            return bytes;
        }
    }

    @Nested
    class Glyphs {

        @Test
        void aPageAtTheGlyphLimitIsRead() {
            assertThat(allText(extract(limits(50, 100_000, 100_000, 10, 100, 8),
                    pdf(content -> text(content, 72, 100, "ABCDEFGHIJ"))))).isEqualTo("ABCDEFGHIJ");
        }

        @Test
        void aPageOverTheGlyphLimitIsRefused() {
            assertTooComplex(() -> extract(limits(50, 100_000, 100_000, 10, 100, 8),
                    pdf(content -> text(content, 72, 100, "ABCDEFGHIJK"))), Limit.GLYPHS_PER_PAGE);
        }

        @Test
        void spacesAreGlyphsToo() {
            assertTooComplex(() -> extract(limits(50, 100_000, 100_000, 10, 100, 8),
                    pdf(content -> text(content, 72, 100, "ABCDE FGHIJ"))), Limit.GLYPHS_PER_PAGE);
        }

        @Test
        void theDocumentHasItsOwnGlyphLimit() {
            byte[] twoPages = pdf(content -> text(content, 72, 100, "ABCDEF"), content -> text(content, 72, 100, "GHIJKL"));

            assertThat(extract(limits(50, 100_000, 100_000, 10, 12, 8), twoPages).lines()).hasSize(2);
            assertTooComplex(() -> extract(limits(50, 100_000, 100_000, 10, 11, 8), twoPages),
                    Limit.GLYPHS_PER_DOCUMENT);
        }
    }

    @Nested
    class Forms {

        @Test
        void formsNestedEightDeepAreRead() throws IOException {
            assertThat(allText(extract(nestedForms(8)))).isEqualTo("inner");
        }

        @Test
        void formsNestedNineDeepAreRefused() throws IOException {
            byte[] bytes = nestedForms(9);

            assertTooComplex(() -> extract(bytes), Limit.FORM_DEPTH);
        }

        @Test
        void aFormThatDrawsItselfIsRefused() throws IOException {
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                PDFormXObject form = new PDFormXObject(rawStream(document, ascii("/Self Do")));
                form.setBBox(PDRectangle.LETTER);
                PDResources formResources = new PDResources();
                formResources.put(COSName.getPDFName("Self"), form);
                form.setResources(formResources);
                page.setResources(new PDResources());
                page.getResources().put(COSName.getPDFName("F0"), form);
                page.setContents(rawStream(document, ascii("/F0 Do")));
                byte[] bytes = save(document);

                assertTooComplex(() -> extract(bytes), Limit.FORM_DEPTH);
            }
        }

        /** A page drawing form 1, which draws form 2, ..., down to form {@code depth}, which shows text. */
        private static byte[] nestedForms(int depth) throws IOException {
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                PDResources fonts = new PDResources();
                COSName font = fonts.add(TestPdfs.HELVETICA);
                PDFormXObject inner = new PDFormXObject(rawStream(document,
                        ascii("BT /" + font.getName() + " 10 Tf 72 700 Td (inner) Tj ET")));
                inner.setBBox(PDRectangle.LETTER);
                inner.setResources(fonts);
                PDFormXObject current = inner;
                for (int level = depth - 1; level >= 1; level--) {
                    PDFormXObject outer = new PDFormXObject(rawStream(document, ascii("/Next Do")));
                    outer.setBBox(PDRectangle.LETTER);
                    PDResources resources = new PDResources();
                    resources.put(COSName.getPDFName("Next"), current);
                    outer.setResources(resources);
                    current = outer;
                }
                page.setResources(new PDResources());
                page.getResources().put(COSName.getPDFName("F0"), current);
                page.setContents(rawStream(document, ascii("/F0 Do")));
                return save(document);
            }
        }
    }

    @Nested
    class Deadline {

        @Test
        void extractionPastTheDeadlineIsRefused() {
            AtomicLong clock = new AtomicLong();
            // Every reading of the clock moves it on by 3 s: the 10 s budget runs out on the 4th check.
            PdfTextExtractor extractor = new PdfTextExtractor(PdfLimits.DEFAULT, () -> clock.getAndAdd(3_000_000_000L));
            byte[] pdf = pdf(content -> text(content, 72, 100, "a"), content -> text(content, 72, 100, "b"),
                    content -> text(content, 72, 100, "c"), content -> text(content, 72, 100, "d"));

            assertTooComplex(() -> extractor.extract(file(pdf)), Limit.EXTRACTION_TIME);
        }

        @Test
        void extractionWithinTheDeadlineSucceeds() {
            PdfTextExtractor extractor = new PdfTextExtractor(PdfLimits.DEFAULT, () -> 0L);

            assertThat(extractor.extract(file(pdf(content -> text(content, 72, 100, "a")))).lines()).hasSize(1);
        }
    }

    @Nested
    class Encryption {

        @Test
        void aPdfThatNeedsAPasswordIsRefusedWithAClearMessage() throws IOException {
            byte[] bytes = encrypted("owner-secret", "user-secret", new AccessPermission());

            assertThatThrownBy(() -> extract(bytes))
                    .isInstanceOfSatisfying(PdfExtractionException.class, e -> {
                        assertThat(e.reason()).isEqualTo(Reason.PASSWORD_REQUIRED);
                        assertThat(e.limit()).isNull();
                        assertThat(e.getCause()).isNull();
                    })
                    .hasMessageContaining("password-protected");
        }

        @Test
        void aPdfWithAnEmptyUserPasswordIsReadDespiteItsRestrictions() throws IOException {
            AccessPermission nothingAllowed = new AccessPermission();
            nothingAllowed.setCanExtractContent(false);
            nothingAllowed.setCanPrint(false);
            nothingAllowed.setCanModify(false);
            nothingAllowed.setCanExtractForAccessibility(false);

            assertThat(allText(extract(encrypted("owner-secret", "", nothingAllowed))))
                    .isEqualTo("Beginning balance on April 1, 2026 1,000.00");
        }

        private static byte[] encrypted(String ownerPassword, String userPassword, AccessPermission permission)
                throws IOException {
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    text(content, 72, 100, "Beginning balance on April 1, 2026 1,000.00");
                }
                StandardProtectionPolicy policy = new StandardProtectionPolicy(ownerPassword, userPassword, permission);
                policy.setEncryptionKeyLength(256);
                document.protect(policy);
                return save(document);
            }
        }
    }

    @Nested
    class InertContent {

        @Test
        void actionsEmbeddedFilesFormsLinksAndMetadataAreNeverRead() throws IOException {
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    text(content, 72, 100, "Visible statement text");
                }
                // JavaScript run on open
                document.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('" + MARKER + "');"));
                // Embedded file
                PDEmbeddedFile embedded = new PDEmbeddedFile(document, new ByteArrayInputStream(ascii(MARKER)));
                PDComplexFileSpecification spec = new PDComplexFileSpecification();
                spec.setFile(MARKER + ".txt");
                spec.setEmbeddedFile(embedded);
                PDEmbeddedFilesNameTreeNode files = new PDEmbeddedFilesNameTreeNode();
                files.setNames(Map.of(MARKER, spec));
                PDDocumentNameDictionary names = new PDDocumentNameDictionary(document.getDocumentCatalog());
                names.setEmbeddedFiles(files);
                document.getDocumentCatalog().setNames(names);
                // Form field with a value
                PDAcroForm form = new PDAcroForm(document);
                document.getDocumentCatalog().setAcroForm(form);
                PDTextField field = new PDTextField(form);
                field.setPartialName("field");
                form.getFields().add(field);
                field.getCOSObject().setString(COSName.V, MARKER);
                // Link annotation
                PDAnnotationLink link = new PDAnnotationLink();
                link.setRectangle(new PDRectangle(72, 600, 100, 20));
                PDActionURI uri = new PDActionURI();
                uri.setURI("https://example.invalid/" + MARKER);
                link.setAction(uri);
                link.setContents(MARKER);
                page.getAnnotations().add(link);
                // Metadata
                PDDocumentInformation info = new PDDocumentInformation();
                info.setTitle(MARKER);
                info.setAuthor(MARKER);
                document.setDocumentInformation(info);

                PdfDocumentText text = extract(save(document));

                assertThat(allText(text)).isEqualTo("Visible statement text").doesNotContain(MARKER);
            }
        }
    }

    @Nested
    class BrokenFiles {

        @Test
        void bytesThatAreNotAPdfAreUnreadable() {
            assertUnreadable(ascii("Date,Description,Amount\n04/01/2026,COFFEE,-4.50\n"));
        }

        @Test
        void aPdfHeaderFollowedByGarbageIsUnreadable() {
            assertUnreadable(ascii("%PDF-1.7\n" + MARKER + " this is not a PDF body\n"));
        }

        @Test
        void aTruncatedPdfIsReadOrRefusedAsUnreadable() {
            byte[] whole = pdf(content -> text(content, 72, 100, "Ending balance"));
            for (int length : List.of(whole.length / 4, whole.length / 2, whole.length - 20)) {
                byte[] truncated = Arrays.copyOf(whole, length);
                try {
                    extract(truncated);
                } catch (PdfExtractionException e) {
                    assertThat(e.reason()).as("truncated to %d bytes", length).isEqualTo(Reason.UNREADABLE);
                    assertThat(e.getCause()).isNull();
                }
            }
        }

        private static void assertUnreadable(byte[] bytes) {
            assertThatThrownBy(() -> extract(bytes))
                    .isInstanceOfSatisfying(PdfExtractionException.class, e -> {
                        assertThat(e.reason()).isEqualTo(Reason.UNREADABLE);
                        assertThat(e.getCause()).isNull();
                    })
                    .hasMessage("The file is not a readable PDF");
        }
    }

    @Nested
    class Messages {

        @Test
        void limitMessagesNameTheLimitOnly() {
            assertThat(PdfExtractionException.tooComplex(Limit.PAGES, 50))
                    .hasMessage("The PDF exceeds the processing limits: it has more than 50 pages");
            assertThat(PdfExtractionException.tooComplex(Limit.DECODED_BYTES, 64L * 1024 * 1024))
                    .hasMessage("The PDF exceeds the processing limits: it has more than 64 MB of decoded content");
            assertThat(PdfExtractionException.tooComplex(Limit.DECODED_BYTES_PER_PAGE, 1000L))
                    .hasMessage("The PDF exceeds the processing limits: it has a page with more than 1000 bytes of "
                            + "decoded content");
            assertThat(PdfExtractionException.tooComplex(Limit.EXTRACTION_TIME, "10 s"))
                    .hasMessage("The PDF exceeds the processing limits: it has content that takes longer than 10 s "
                            + "to read");
        }

        @Test
        void limitsMustBePositive() {
            assertThatThrownBy(() -> limits(0, 1, 1, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PdfLimits(1, 1, 1, 1, 1, 1, Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class NoDiskWrites {

        private static final Path FONT_CACHE = Path.of(System.getProperty("user.home"), ".pdfbox.cache");
        private static final Path TEMP = Path.of(System.getProperty("java.io.tmpdir"));

        @Test
        void fontsAreMappedWithoutScanningOrCachingSystemFonts() throws IOException {
            assertThat(NoSystemFontsMapper.isInstalled()).isTrue();
            FileTime cacheBefore = Files.exists(FONT_CACHE) ? Files.getLastModifiedTime(FONT_CACHE) : null;
            Set<Path> scratchBefore = pdfboxScratchFiles();

            // A TrueType font that isn't embedded: PDFBox's default mapper would scan system fonts
            // for it and write a cache file.
            PdfDocumentText text = extract(withNonEmbeddedTrueTypeFont("Arial", "Hello"));

            assertThat(allText(text)).isEqualTo("Hello");
            FileTime cacheAfter = Files.exists(FONT_CACHE) ? Files.getLastModifiedTime(FONT_CACHE) : null;
            assertThat(cacheAfter).isEqualTo(cacheBefore);
            assertThat(pdfboxScratchFiles()).isEqualTo(scratchBefore);
        }

        private static Set<Path> pdfboxScratchFiles() throws IOException {
            try (Stream<Path> files = Files.list(TEMP)) {
                return files.filter(path -> path.getFileName().toString().startsWith("PDFBox"))
                        .collect(Collectors.toSet());
            }
        }

        private static byte[] withNonEmbeddedTrueTypeFont(String baseFont, String text) throws IOException {
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                COSDictionary fontDictionary = new COSDictionary();
                fontDictionary.setItem(COSName.TYPE, COSName.FONT);
                fontDictionary.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
                fontDictionary.setName(COSName.BASE_FONT, baseFont);
                fontDictionary.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
                fontDictionary.setInt(COSName.FIRST_CHAR, 32);
                fontDictionary.setInt(COSName.LAST_CHAR, 126);
                COSArray widths = new COSArray();
                for (int c = 32; c <= 126; c++) {
                    widths.add(COSInteger.get(600));
                }
                fontDictionary.setItem(COSName.WIDTHS, widths);
                PDFont font = new PDTrueTypeFont(fontDictionary);
                page.setResources(new PDResources());
                COSName name = page.getResources().add(font);
                page.setContents(new PDStream(document, new ByteArrayInputStream(
                        ascii("BT /" + name.getName() + " 10 Tf 72 692 Td (" + text + ") Tj ET"))));
                return save(document);
            }
        }
    }
}

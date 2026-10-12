package com.finalysis.ingestion.pdf;

import com.finalysis.ingestion.ImportFile;
import com.finalysis.ingestion.pdf.PdfExtractionException.Limit;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Reads the positioned text of a PDF statement with Apache PDFBox, within {@link PdfLimits}
 * (SEC-10). Bank-agnostic: layout parsers interpret the {@link PdfDocumentText} it returns.
 *
 * <h2>Safety</h2>
 * <ul>
 *   <li><b>Memory only:</b> the file is loaded from its bytes with a main-memory stream cache, so
 *       nothing is written to disk; {@link NoSystemFontsMapper} keeps PDFBox from scanning system
 *       fonts and writing a font cache.</li>
 *   <li><b>Encryption:</b> a PDF that needs a password to open is refused. One encrypted with an
 *       empty user password (owner restrictions only, such as no printing) is read: any viewer
 *       opens it without a secret. Permission flags are ignored.</li>
 *   <li><b>Decoded size, measured before anything is extracted:</b> PDFBox decodes a stream fully
 *       into memory when it reads it, so a small compressed stream could expand to gigabytes. Every
 *       non-image stream is first decoded here through PDFBox's own filters into a counting sink
 *       that stops at the limit, plain objects before compressed ones, so object streams are
 *       measured before PDFBox opens them. Each page's content streams and the forms it can reach
 *       are then checked against the per-page limit. Image streams are skipped: text extraction
 *       never decodes them.</li>
 *   <li><b>Inert content:</b> only page content streams are read. Actions and JavaScript, embedded
 *       files, forms, annotations, outlines and metadata are never touched.</li>
 *   <li><b>Errors</b> are {@link PdfExtractionException}s with fixed messages. PDFBox's exceptions
 *       are dropped, never chained or logged, because their messages can quote content.</li>
 * </ul>
 *
 * <h2>Lines and words (fingerprint-relevant)</h2>
 * Descriptions, and so transaction fingerprints, are built from these words, so the rules below are
 * locked by tests and changing them changes fingerprints (docs/plans/phase-3b.md §1.3):
 * <ul>
 *   <li>Only horizontal glyphs are kept (text direction 0 after page rotation); rotated ones and
 *       glyphs without Unicode text are dropped and counted.</li>
 *   <li>A line is the glyphs whose baselines are within 0.25 × the font size of the line's topmost
 *       glyph, on one page. Lines run top to bottom and glyphs left to right, whatever order the
 *       PDF drew them in.</li>
 *   <li>A glyph repeating the same text within 0.5 pt of a kept glyph in both directions (fake
 *       bold) is kept once.</li>
 *   <li>A whitespace glyph ends a word and is not part of it; so does a horizontal gap of at least
 *       0.3 × the font size of the glyph before the gap.</li>
 *   <li>Line numbers are 1-based and global across pages.</li>
 * </ul>
 */
public final class PdfTextExtractor {

    static {
        // Before PDFBox creates its first font object anywhere in this JVM.
        NoSystemFontsMapper.install();
    }

    static final float LINE_TOLERANCE = 0.25f;
    static final float WORD_GAP = 0.3f;
    static final float DUPLICATE_TOLERANCE = 0.5f;

    private static final Set<COSName> IMAGE_FILTERS = Set.of(COSName.DCT_DECODE, COSName.DCT_DECODE_ABBREVIATION,
            COSName.JPX_DECODE, COSName.JBIG2_DECODE, COSName.CCITTFAX_DECODE, COSName.CCITTFAX_DECODE_ABBREVIATION);
    private static final int GLYPHS_PER_DEADLINE_CHECK = 1000;
    private static final int BYTES_PER_DEADLINE_CHECK = 1024 * 1024;

    private final PdfLimits limits;
    private final LongSupplier nanoTime;

    public PdfTextExtractor() {
        this(PdfLimits.DEFAULT);
    }

    public PdfTextExtractor(PdfLimits limits) {
        this(limits, System::nanoTime);
    }

    /** @param nanoTime the clock for the extraction deadline, replaceable in tests */
    PdfTextExtractor(PdfLimits limits, LongSupplier nanoTime) {
        this.limits = limits;
        this.nanoTime = nanoTime;
    }

    /**
     * @throws PdfExtractionException if the PDF needs a password, exceeds a limit, or can't be read
     */
    public PdfDocumentText extract(ImportFile file) {
        Deadline deadline = new Deadline();
        try (PDDocument document = Loader.loadPDF(file.bytes(), "", null, null,
                MemoryUsageSetting.setupMainMemoryOnly(limits.maxDecodedBytes()).streamCache)) {
            if (document.getNumberOfPages() > limits.maxPages()) {
                throw PdfExtractionException.tooComplex(Limit.PAGES, limits.maxPages());
            }
            Map<COSStream, Long> decodedSizes = measureStreams(document.getDocument(), deadline);
            checkPages(document, decodedSizes, deadline);
            return new GlyphCollector(deadline).collect(document);
        } catch (PdfExtractionException e) {
            throw e;
        } catch (InvalidPasswordException e) {
            throw PdfExtractionException.passwordRequired();
        } catch (IOException | RuntimeException e) {
            throw PdfExtractionException.unreadable();
        } catch (StackOverflowError e) {
            // Deeply nested objects in a hostile file; PDFBox parses them recursively.
            throw PdfExtractionException.tooComplex(Limit.NESTING, null);
        }
    }

    // ------------------------------------------------------------------ decoded size

    /**
     * Decodes every non-image stream into a counting sink, plain objects first (in object number
     * order), then objects inside object streams, failing as soon as the total passes the limit.
     */
    private Map<COSStream, Long> measureStreams(COSDocument cos, Deadline deadline) {
        List<Map.Entry<COSObjectKey, Long>> entries = new ArrayList<>(cos.getXrefTable().entrySet());
        entries.sort(Comparator.<Map.Entry<COSObjectKey, Long>>comparingInt(entry -> entry.getValue() < 0 ? 1 : 0)
                .thenComparing(Map.Entry::getKey));
        Map<COSStream, Long> sizes = new IdentityHashMap<>();
        long total = 0;
        for (Map.Entry<COSObjectKey, Long> entry : entries) {
            deadline.check();
            COSBase object = dereference(cos, entry.getKey());
            if (object instanceof COSStream stream && !sizes.containsKey(stream) && !isImage(stream)) {
                long size = decodedSize(stream, limits.maxDecodedBytes() - total, deadline,
                        () -> PdfExtractionException.tooComplex(Limit.DECODED_BYTES, limits.maxDecodedBytes()));
                sizes.put(stream, size);
                total += size;
            }
        }
        return sizes;
    }

    private static COSBase dereference(COSDocument cos, COSObjectKey key) {
        try {
            return cos.getObjectFromPool(key).getObject();
        } catch (RuntimeException e) {
            // A broken object; PDFBox can't read it later either, so it decodes nothing.
            return null;
        }
    }

    private static boolean isImage(COSStream stream) {
        if (COSName.IMAGE.equals(stream.getCOSName(COSName.SUBTYPE))) {
            return true;
        }
        return filterNames(stream).stream().anyMatch(IMAGE_FILTERS::contains);
    }

    private static List<COSName> filterNames(COSStream stream) {
        COSBase filters = stream.getFilters();
        if (filters instanceof COSName name) {
            return List.of(name);
        }
        List<COSName> names = new ArrayList<>();
        if (filters instanceof COSArray array) {
            for (COSBase item : array) {
                if (item instanceof COSName name) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * The stream's decoded size, decoding with PDFBox's filters into sinks that throw once more than
     * {@code budget} bytes arrive. A stream PDFBox can't decode (unknown filter, corrupt data)
     * counts what was decoded before the error, as PDFBox itself tolerates such streams.
     */
    private long decodedSize(COSStream stream, long budget, Deadline deadline,
                             Supplier<PdfExtractionException> exceeded) {
        CountingSink sink = new CountingSink(budget, deadline, exceeded);
        try (InputStream raw = stream.createRawInputStream()) {
            List<COSName> names = filterNames(stream);
            InputStream input = raw;
            for (int i = 0; i < names.size(); i++) {
                Filter filter = FilterFactory.INSTANCE.getFilter(names.get(i));
                if (i == names.size() - 1) {
                    filter.decode(input, sink, stream, i);
                } else {
                    // Intermediate stages are bounded by the same budget.
                    BoundedBuffer stage = new BoundedBuffer(budget, deadline, exceeded);
                    filter.decode(input, stage, stream, i);
                    input = new ByteArrayInputStream(stage.toByteArray());
                }
            }
            if (names.isEmpty()) {
                raw.transferTo(sink);
            }
        } catch (PdfExtractionException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // tolerated, see above
        }
        return sink.count;
    }

    /** Each page's content streams plus the form XObjects it can reach, against the per-page limit. */
    private void checkPages(PDDocument document, Map<COSStream, Long> decodedSizes, Deadline deadline) {
        for (PDPage page : document.getPages()) {
            deadline.check();
            Set<COSStream> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            long size = 0;
            Iterator<PDStream> contents = page.getContentStreams();
            while (contents.hasNext()) {
                size += sizeOnce(contents.next().getCOSObject(), decodedSizes, seen, deadline);
            }
            COSDictionary resources = page.getResources() == null ? null : page.getResources().getCOSObject();
            size += formSizes(resources, 0, decodedSizes, seen, deadline);
            if (size > limits.maxDecodedBytesPerPage()) {
                throw PdfExtractionException.tooComplex(Limit.DECODED_BYTES_PER_PAGE, limits.maxDecodedBytesPerPage());
            }
        }
    }

    private long formSizes(COSDictionary resources, int depth, Map<COSStream, Long> decodedSizes,
                           Set<COSStream> seen, Deadline deadline) {
        if (resources == null || depth > limits.maxFormDepth()) {
            return 0;
        }
        COSDictionary xObjects = resources.getCOSDictionary(COSName.XOBJECT);
        if (xObjects == null) {
            return 0;
        }
        long size = 0;
        for (COSName name : xObjects.keySet()) {
            if (xObjects.getDictionaryObject(name) instanceof COSStream form
                    && COSName.FORM.equals(form.getCOSName(COSName.SUBTYPE)) && !seen.contains(form)) {
                size += sizeOnce(form, decodedSizes, seen, deadline);
                size += formSizes(form.getCOSDictionary(COSName.RESOURCES), depth + 1, decodedSizes, seen, deadline);
            }
        }
        return size;
    }

    private long sizeOnce(COSStream stream, Map<COSStream, Long> decodedSizes, Set<COSStream> seen,
                          Deadline deadline) {
        if (!seen.add(stream)) {
            return 0;
        }
        Long known = decodedSizes.get(stream);
        if (known != null) {
            return known;
        }
        // Not reachable through the cross-reference table; measure it against the page budget.
        return decodedSize(stream, limits.maxDecodedBytesPerPage(), deadline,
                () -> PdfExtractionException.tooComplex(Limit.DECODED_BYTES_PER_PAGE, limits.maxDecodedBytesPerPage()));
    }

    // ------------------------------------------------------------------ glyphs → lines → words

    /** One kept glyph, in page coordinates growing downwards. */
    private record Glyph(String text, float x, float y, float width, float fontSize, boolean bold, int order) {

        float xEnd() {
            return x + width;
        }

        boolean isWhitespace() {
            return text.codePoints().allMatch(PdfTextExtractor::isWhitespace);
        }
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    /** Collects glyphs page by page with PDFBox's stripper callbacks, enforcing glyph and form limits. */
    private final class GlyphCollector extends PDFTextStripper {

        private final Deadline deadline;
        private final List<PdfPage> pages = new ArrayList<>();
        private List<Glyph> glyphs = new ArrayList<>();
        private int pageNumber;
        private int pageGlyphs;
        private int documentGlyphs;
        private int rotated;
        private int unmapped;
        private int formDepth;
        private int nextLineNumber = 1;

        GlyphCollector(Deadline deadline) {
            this.deadline = deadline;
            setSortByPosition(false);
            setShouldSeparateByBeads(false);
        }

        PdfDocumentText collect(PDDocument document) throws IOException {
            getText(document);
            return new PdfDocumentText(pages, rotated, unmapped);
        }

        /** Every page, including those without a content stream, which the stripper would skip. */
        @Override
        protected void processPages(PDPageTree pageTree) throws IOException {
            for (PDPage page : pageTree) {
                processPage(page);
            }
        }

        @Override
        public void processPage(PDPage page) throws IOException {
            deadline.check();
            pageNumber++;
            glyphs = new ArrayList<>();
            pageGlyphs = 0;
            if (page.hasContents()) {
                super.processPage(page);
            }
            PDRectangle box = page.getCropBox();
            boolean sideways = page.getRotation() % 180 != 0;
            pages.add(new PdfPage(pageNumber, sideways ? box.getHeight() : box.getWidth(),
                    sideways ? box.getWidth() : box.getHeight(), lines()));
        }

        @Override
        public void showForm(PDFormXObject form) throws IOException {
            enterForm();
            try {
                super.showForm(form);
            } finally {
                formDepth--;
            }
        }

        @Override
        public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
            enterForm();
            try {
                super.showTransparencyGroup(form);
            } finally {
                formDepth--;
            }
        }

        private void enterForm() {
            if (++formDepth > limits.maxFormDepth()) {
                throw PdfExtractionException.tooComplex(Limit.FORM_DEPTH, limits.maxFormDepth());
            }
        }

        /** Replaces the stripper's own text building: glyphs are only collected here. */
        @Override
        protected void processTextPosition(TextPosition text) {
            if (++pageGlyphs > limits.maxGlyphsPerPage()) {
                throw PdfExtractionException.tooComplex(Limit.GLYPHS_PER_PAGE, limits.maxGlyphsPerPage());
            }
            if (++documentGlyphs > limits.maxGlyphsPerDocument()) {
                throw PdfExtractionException.tooComplex(Limit.GLYPHS_PER_DOCUMENT, limits.maxGlyphsPerDocument());
            }
            if (documentGlyphs % GLYPHS_PER_DEADLINE_CHECK == 0) {
                deadline.check();
            }
            if (text.getDir() != 0) {
                rotated++;
                return;
            }
            String unicode = text.getUnicode();
            if (unicode == null || unicode.isEmpty()) {
                unmapped++;
                return;
            }
            glyphs.add(new Glyph(unicode, text.getXDirAdj(), text.getYDirAdj(), text.getWidthDirAdj(),
                    text.getFontSizeInPt(), isBold(text.getFont()), glyphs.size()));
        }

        private List<PdfLine> lines() {
            List<Glyph> byPosition = new ArrayList<>(glyphs);
            byPosition.sort(Comparator.comparingDouble(Glyph::y).thenComparingDouble(Glyph::x)
                    .thenComparingInt(Glyph::order));
            List<PdfLine> lines = new ArrayList<>();
            int start = 0;
            while (start < byPosition.size()) {
                Glyph top = byPosition.get(start);
                float tolerance = LINE_TOLERANCE * size(top);
                int end = start + 1;
                while (end < byPosition.size() && byPosition.get(end).y() - top.y() <= tolerance) {
                    end++;
                }
                List<PdfWord> words = words(byPosition.subList(start, end));
                if (!words.isEmpty()) {
                    lines.add(new PdfLine(nextLineNumber++, pageNumber, top.y(), words));
                }
                start = end;
            }
            return lines;
        }
    }

    /** Glyphs of one line → words: left to right, duplicates dropped, split at whitespace and gaps. */
    private static List<PdfWord> words(List<Glyph> line) {
        List<Glyph> byX = new ArrayList<>(line);
        byX.sort(Comparator.comparingDouble(Glyph::x).thenComparingInt(Glyph::order));
        List<Glyph> kept = new ArrayList<>();
        for (Glyph glyph : byX) {
            if (!isDuplicate(glyph, kept)) {
                kept.add(glyph);
            }
        }
        List<PdfWord> words = new ArrayList<>();
        List<Glyph> word = new ArrayList<>();
        for (Glyph glyph : kept) {
            if (glyph.isWhitespace()) {
                flush(word, words);
                continue;
            }
            if (!word.isEmpty()) {
                Glyph previous = word.getLast();
                if (glyph.x() - previous.xEnd() >= WORD_GAP * size(previous)) {
                    flush(word, words);
                }
            }
            word.add(glyph);
        }
        flush(word, words);
        return words;
    }

    private static boolean isDuplicate(Glyph glyph, List<Glyph> kept) {
        for (int i = kept.size() - 1; i >= 0; i--) {
            Glyph other = kept.get(i);
            if (glyph.x() - other.x() > DUPLICATE_TOLERANCE) {
                return false;
            }
            if (other.text().equals(glyph.text()) && Math.abs(other.y() - glyph.y()) <= DUPLICATE_TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    private static void flush(List<Glyph> word, List<PdfWord> words) {
        if (word.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Glyph glyph : word) {
            // A multi-character glyph (a ligature) never carries whitespace into a word.
            glyph.text().codePoints().filter(c -> !isWhitespace(c)).forEach(text::appendCodePoint);
        }
        Glyph first = word.getFirst();
        if (!text.isEmpty()) {
            words.add(new PdfWord(text.toString(), first.x(), word.getLast().xEnd(), first.y(), first.fontSize(),
                    first.bold()));
        }
        word.clear();
    }

    /** Font size for tolerances; a degenerate text matrix can report 0. */
    private static float size(Glyph glyph) {
        return Math.max(glyph.fontSize(), 1f);
    }

    private static boolean isBold(PDFont font) {
        if (font == null) {
            return false;
        }
        String name = font.getName();
        if (name != null && name.toLowerCase(Locale.ROOT).contains("bold")) {
            return true;
        }
        PDFontDescriptor descriptor = font.getFontDescriptor();
        return descriptor != null && (descriptor.isForceBold() || descriptor.getFontWeight() >= 700);
    }

    // ------------------------------------------------------------------ limits

    /** The wall-clock budget of one extraction, checked cooperatively. */
    private final class Deadline {

        private final long end = nanoTime.getAsLong() + limits.maxExtractionTime().toNanos();

        void check() {
            if (nanoTime.getAsLong() - end > 0) {
                throw PdfExtractionException.tooComplex(Limit.EXTRACTION_TIME, describe(limits.maxExtractionTime()));
            }
        }

        private static String describe(Duration duration) {
            return duration.toMillisPart() == 0 ? duration.toSeconds() + " s" : duration.toMillis() + " ms";
        }
    }

    /** Counts decoded bytes and throws once more than the budget arrives. */
    private static class CountingSink extends OutputStream {

        private final long budget;
        private final Deadline deadline;
        private final Supplier<PdfExtractionException> exceeded;
        long count;
        private long sinceCheck;

        CountingSink(long budget, Deadline deadline, Supplier<PdfExtractionException> exceeded) {
            this.budget = budget;
            this.deadline = deadline;
            this.exceeded = exceeded;
        }

        @Override
        public void write(int b) {
            accept(1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            accept(length);
        }

        void accept(int length) {
            count += length;
            if (count > budget) {
                throw exceeded.get();
            }
            sinceCheck += length;
            if (sinceCheck >= BYTES_PER_DEADLINE_CHECK) {
                sinceCheck = 0;
                deadline.check();
            }
        }
    }

    /** An intermediate filter stage: kept in memory, within the same budget. */
    private static final class BoundedBuffer extends CountingSink {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        BoundedBuffer(long budget, Deadline deadline, Supplier<PdfExtractionException> exceeded) {
            super(budget, deadline, exceeded);
        }

        @Override
        public void write(int b) {
            accept(1);
            bytes.write(b);
        }

        @Override
        public void write(byte[] data, int offset, int length) {
            accept(length);
            bytes.write(data, offset, length);
        }

        byte[] toByteArray() {
            return bytes.toByteArray();
        }
    }
}

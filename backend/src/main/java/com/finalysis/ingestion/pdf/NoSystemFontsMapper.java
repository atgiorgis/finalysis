package com.finalysis.ingestion.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.fontbox.FontBoxFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.font.CIDFontMapping;
import org.apache.pdfbox.pdmodel.font.FontMapper;
import org.apache.pdfbox.pdmodel.font.FontMapping;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.apache.pdfbox.pdmodel.font.PDCIDSystemInfo;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;

/**
 * Maps every non-embedded font to the Liberation Sans font bundled inside PDFBox (SEC-10).
 *
 * <p>PDFBox's default mapper scans the operating system's fonts the first time it needs one and
 * writes a font cache file to the user's home directory. A statement upload must never touch disk,
 * so this mapper replaces it. Text extraction doesn't need the real font: widths of the standard 14
 * fonts come from PDFBox's bundled metrics, and embedded fonts are read from the PDF itself. Only
 * the glyph outlines (used for rendering, which the app never does) come from the fallback.
 *
 * <p>{@link #install()} must run before PDFBox creates its first font, because PDFBox keeps the
 * first mapper it is asked for. {@link PdfTextExtractor} installs it when its class loads.
 */
final class NoSystemFontsMapper implements FontMapper {

    private static final String FALLBACK_RESOURCE = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

    private static final NoSystemFontsMapper INSTANCE = new NoSystemFontsMapper();

    private final TrueTypeFont fallback;

    private NoSystemFontsMapper() {
        try (InputStream in = FontMapper.class.getResourceAsStream(FALLBACK_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("PDFBox's bundled fallback font is missing");
            }
            fallback = new TTFParser().parse(new RandomAccessReadBuffer(in));
        } catch (IOException e) {
            throw new UncheckedIOException("PDFBox's bundled fallback font can't be read", e);
        }
    }

    /** Replaces PDFBox's font mapper; idempotent. */
    static void install() {
        FontMappers.set(INSTANCE);
    }

    static boolean isInstalled() {
        return FontMappers.instance() == INSTANCE;
    }

    @Override
    public FontMapping<TrueTypeFont> getTrueTypeFont(String baseFont, PDFontDescriptor fontDescriptor) {
        return new FontMapping<>(fallback, true);
    }

    @Override
    public FontMapping<FontBoxFont> getFontBoxFont(String baseFont, PDFontDescriptor fontDescriptor) {
        return new FontMapping<>(fallback, true);
    }

    @Override
    public CIDFontMapping getCIDFont(String baseFont, PDFontDescriptor fontDescriptor,
                                     PDCIDSystemInfo cidSystemInfo) {
        return new CIDFontMapping(null, fallback, true);
    }
}

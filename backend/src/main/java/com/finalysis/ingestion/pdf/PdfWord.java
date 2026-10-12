package com.finalysis.ingestion.pdf;

import java.util.Objects;

/**
 * One word of extracted PDF text with its position, in points from the page's top-left corner
 * (after page rotation). See {@link PdfTextExtractor} for how glyphs become words.
 *
 * @param text      the word's characters, never empty and never containing whitespace
 * @param x         left edge of the first glyph
 * @param xEnd      right edge of the last glyph
 * @param baselineY baseline of the first glyph, growing downwards
 * @param fontSize  font size of the first glyph, in points
 * @param bold      whether the first glyph's font is bold
 */
public record PdfWord(String text, float x, float xEnd, float baselineY, float fontSize, boolean bold) {

    public PdfWord {
        Objects.requireNonNull(text, "text");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("A word has at least one character");
        }
    }

    /** Position and size only: the text is statement content (SEC-10). */
    @Override
    public String toString() {
        return "PdfWord[" + text.length() + " chars at x=" + x + ", y=" + baselineY + "]";
    }
}

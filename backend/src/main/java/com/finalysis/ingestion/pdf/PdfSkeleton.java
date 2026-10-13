package com.finalysis.ingestion.pdf;

import com.finalysis.ingestion.ImportFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The masked layout skeleton of a PDF (docs/plans/phase-3b.md §6): one line per extracted line with
 * its kind, its words' x positions and its text masked, then a summary. When a real statement
 * fails, its owner runs this locally and shares the output instead of the file.
 *
 * <h2>What is shown</h2>
 * <ul>
 *   <li>Every letter becomes {@code x} and every digit {@code 9}. Spaces and the punctuation that
 *       carries structure ({@code $ , . - / * # : ( ) & + '}) are kept; any other character becomes
 *       {@code ~}.</li>
 *   <li>Each run of words starts with its x position ({@code @ 36}); a gap of more than 12 pt
 *       between words starts a new run, marked {@code |}.</li>
 *   <li>With {@code showLayoutWords}, words that are exactly one of a detected layout's fixed words
 *       ({@link PdfSkeletonLayout#layoutWords()}; every layout's when none is detected) are shown as
 *       they are. Opt-in, because a description can share such a word.</li>
 * </ul>
 *
 * <h2>What is never shown</h2>
 * Unmasked statement text, amounts, metadata, the file name, and PDFBox's messages. Detection,
 * parse and extraction results are fixed words and counts. This class reads nothing but the bytes
 * it is given and writes nothing but the lines it returns.
 */
public final class PdfSkeleton {

    /** Words further apart than this, in points, are in different columns. */
    static final float COLUMN_GAP = 12f;

    /** The kind of every line when no layout recognizes the file. */
    static final String UNCLASSIFIED = "UNCLASSIFIED";

    /** Kept by the mask: spaces and the punctuation that carries a layout's structure. */
    private static final String KEPT = " $,.-/*#:()&+'";

    private final List<PdfSkeletonLayout> layouts;
    private final PdfTextExtractor extractor;

    public PdfSkeleton(List<PdfSkeletonLayout> layouts) {
        this(layouts, new PdfTextExtractor());
    }

    PdfSkeleton(List<PdfSkeletonLayout> layouts, PdfTextExtractor extractor) {
        this.layouts = List.copyOf(layouts);
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    /**
     * The skeleton of a PDF's bytes, line by line.
     *
     * @throws PdfExtractionException if the PDF can't be extracted; {@link #failure} describes it
     */
    public List<String> render(byte[] pdf, boolean showLayoutWords) {
        return render(extractor.extract(ImportFile.of("statement.pdf", pdf)), showLayoutWords);
    }

    List<String> render(PdfDocumentText text, boolean showLayoutWords) {
        List<String> out = new ArrayList<>();

        List<String> detectLines = new ArrayList<>();
        List<PdfSkeletonLayout> detected = new ArrayList<>();
        for (PdfSkeletonLayout layout : layouts) {
            Map<String, Boolean> markers = layout.markers(text);
            boolean definite = markers.values().stream().allMatch(Boolean::booleanValue);
            if (definite) {
                detected.add(layout);
            }
            detectLines.add("detect " + layout.parserId() + ": " + (definite ? "DEFINITE" : "NONE") + " (markers: "
                    + markers.entrySet().stream().map(m -> m.getKey() + " " + (m.getValue() ? "✓" : "✗"))
                    .collect(Collectors.joining(" ")) + ")");
        }
        PdfSkeletonLayout layout = detected.size() == 1 ? detected.getFirst() : null;
        out.addAll(detectLines);

        List<PdfLine> lines = text.lines();
        List<String> kinds = layout == null ? null : layout.classify(text);
        Set<String> shown = !showLayoutWords ? Set.of()
                : layout != null ? layout.layoutWords()
                : layouts.stream().flatMap(l -> l.layoutWords().stream()).collect(Collectors.toCollection(TreeSet::new));
        for (int i = 0; i < lines.size(); i++) {
            PdfLine line = lines.get(i);
            out.add(String.format(Locale.ROOT, "p%d L%04d %-17s %s", line.page(), line.number(),
                    kinds == null ? UNCLASSIFIED : kinds.get(i), maskedLine(line, shown)));
        }

        out.add("");
        out.add("== summary ==");
        out.add("pages: " + text.pages().size() + " (lines per page: "
                + text.pages().stream().map(p -> String.valueOf(p.lines().size())).collect(Collectors.joining(", "))
                + ")");
        out.add("lines: " + lines.size());
        out.add("dropped glyphs: rotated " + text.rotatedGlyphs() + ", unmapped " + text.unmappedGlyphs());
        out.add("lines per kind:");
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (layout == null) {
            counts.put(UNCLASSIFIED, lines.size());
        } else {
            layout.kinds().forEach(kind -> counts.put(kind, 0));
            kinds.forEach(kind -> counts.merge(kind, 1, Integer::sum));
        }
        counts.forEach((kind, count) -> out.add(String.format(Locale.ROOT, "  %-17s %d", kind, count)));
        out.addAll(detectLines);
        if (layout != null) {
            out.add("parse: " + layout.parseStatus(text));
        } else if (detected.isEmpty()) {
            out.add("parse: not run (no layout recognized)");
        } else {
            out.add("parse: not run (" + detected.size() + " layouts recognized)");
        }
        out.add("reconciliation: not run");
        for (PdfSkeletonLayout guessing : layout == null ? layouts : List.of(layout)) {
            out.add("open layout guesses (" + guessing.parserId() + "):");
            guessing.openGuesses().forEach(guess -> out.add("  - " + guess));
        }
        return out;
    }

    /** Runs of words separated by column gaps, each with its x position; words masked unless shown. */
    static String maskedLine(PdfLine line, Set<String> shown) {
        StringBuilder out = new StringBuilder();
        PdfWord previous = null;
        for (PdfWord word : line.words()) {
            if (previous == null || word.x() - previous.xEnd() > COLUMN_GAP) {
                if (previous != null) {
                    out.append(" | ");
                }
                out.append(String.format(Locale.ROOT, "@%3d ", Math.round(word.x())));
            } else {
                out.append(' ');
            }
            out.append(shown.contains(word.text()) ? word.text() : mask(word.text()));
            previous = word;
        }
        return out.toString();
    }

    static String mask(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            if (Character.isLetter(c)) {
                out.append('x');
            } else if (Character.isDigit(c)) {
                out.append('9');
            } else if (c < 0x80 && KEPT.indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append('~');
            }
        });
        return out.toString();
    }

    /** Why extraction failed, in fixed words: never PDFBox's message or the PDF's content. */
    public static String failure(PdfExtractionException e) {
        return switch (e.reason()) {
            case PASSWORD_REQUIRED -> "password required";
            case TOO_COMPLEX -> "too complex: " + e.limit();
            case UNREADABLE -> "unreadable";
        };
    }
}

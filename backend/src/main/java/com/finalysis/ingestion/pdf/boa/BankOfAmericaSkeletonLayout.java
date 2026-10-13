package com.finalysis.ingestion.pdf.boa;

import com.finalysis.ingestion.pdf.PdfDocumentText;
import com.finalysis.ingestion.pdf.PdfSkeletonLayout;
import com.finalysis.ingestion.pdf.boa.BankOfAmericaLineClassifier.LineKind;
import com.finalysis.ingestion.pdf.boa.BankOfAmericaLineClassifier.Markers;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.Set;

/**
 * The Bank of America checking layout as the skeleton tool sees it: the markers and classification
 * of {@link BankOfAmericaLineClassifier}, and the vocabulary and guesses of
 * {@link BankOfAmericaLayout} (docs/plans/phase-3b.md §6).
 */
public final class BankOfAmericaSkeletonLayout implements PdfSkeletonLayout {

    @Override
    public String parserId() {
        return BankOfAmericaCheckingParser.ID;
    }

    @Override
    public SequencedMap<String, Boolean> markers(PdfDocumentText text) {
        Markers markers = BankOfAmericaLineClassifier.markers(text);
        SequencedMap<String, Boolean> named = new LinkedHashMap<>();
        named.put("period", markers.period());
        named.put("beginning", markers.beginningBalance());
        named.put("deposits", markers.deposits());
        named.put("withdrawals", markers.withdrawals());
        named.put("ending", markers.endingBalance());
        return named;
    }

    @Override
    public List<String> kinds() {
        return Arrays.stream(LineKind.values()).map(Enum::name).toList();
    }

    @Override
    public List<String> classify(PdfDocumentText text) {
        return BankOfAmericaLineClassifier.classify(text).stream().map(line -> line.kind().name()).toList();
    }

    @Override
    public Set<String> layoutWords() {
        return BankOfAmericaLayout.LAYOUT_WORDS;
    }

    @Override
    public List<String> openGuesses() {
        return BankOfAmericaLayout.OPEN_GUESSES;
    }

    /** Step 6 replaces this with the parser's result: passed, or failed at a line number. */
    @Override
    public String parseStatus(PdfDocumentText text) {
        return "not implemented";
    }
}

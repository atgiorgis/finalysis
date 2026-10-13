package com.finalysis.ingestion.pdf.boa;

import com.finalysis.ingestion.FormatMatch;
import com.finalysis.ingestion.ParseContext;
import com.finalysis.ingestion.ParsedStatement;
import com.finalysis.ingestion.StatementParseException;
import com.finalysis.ingestion.pdf.PdfDocumentText;
import com.finalysis.ingestion.pdf.PdfStatementParser;
import org.springframework.stereotype.Component;

/**
 * The Bank of America checking statement layout (ING-08, docs/plans/phase-3b.md §3).
 *
 * <p>Detection is structural and never uses the institution's name (§1.5): page 1 must carry the
 * period line, the beginning balance with an amount, the deposits and withdrawals summary lines
 * with amounts below it, and the ending balance with an amount below those. Section headings are
 * not markers, since a statement without activity may print none.
 *
 * <p>Reading the statement comes in step 6. Until then a recognized file fails with a parse error
 * that says so, rather than being recorded as an unparsed upload it isn't.
 */
@Component
public class BankOfAmericaCheckingParser implements PdfStatementParser {

    public static final String ID = "boa-checking";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public FormatMatch detect(PdfDocumentText text) {
        return BankOfAmericaLineClassifier.markers(text).all() ? FormatMatch.DEFINITE : FormatMatch.NONE;
    }

    @Override
    public ParsedStatement parse(PdfDocumentText text, ParseContext context) {
        throw new StatementParseException(null, "The statement layout was recognized (" + ID
                + "), but reading it is not implemented yet; nothing was imported");
    }
}

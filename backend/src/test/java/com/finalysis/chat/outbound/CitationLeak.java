package com.finalysis.chat.outbound;

import com.finalysis.chat.citation.Citation;

/** A rule fixture for {@code ChatToolPathTest}: an outbound class that reads a citation, as a leak would. */
public class CitationLeak {

    @SuppressWarnings("unused")
    String leak(Citation.CitedTxn txn) {
        return txn.sourceLine().text();
    }
}

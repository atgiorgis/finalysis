package com.finalysis.ingestion;

/**
 * What an import did: imported a statement (201 Created) or, for a PDF no layout parser
 * recognized, recorded an unparsed upload (202 Accepted, ING-13).
 */
public sealed interface ImportOutcome {

    record Imported(ImportSummary summary) implements ImportOutcome {
    }

    record Unparsed(UnparsedUploadResponse upload) implements ImportOutcome {
    }
}

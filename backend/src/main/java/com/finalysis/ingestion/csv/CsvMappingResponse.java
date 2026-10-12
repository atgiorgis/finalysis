package com.finalysis.ingestion.csv;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A saved CSV mapping. {@code usedAt} is non-null once an import has used it, which locks the
 * fingerprint fields (see {@link CsvMapping}). {@code warnings} is empty except after a confirmed
 * change to a locked field.
 */
public record CsvMappingResponse(
        Long accountId,
        String delimiter,
        String encoding,
        boolean headerRow,
        int skipRows,
        String headerSignature,
        String dateColumn,
        String dateFormat,
        String postDateColumn,
        List<String> descriptionColumns,
        CsvAmountMode amountMode,
        String amountColumn,
        String debitColumn,
        String creditColumn,
        CsvSign sign,
        String balanceColumn,
        String checkNumberColumn,
        OffsetDateTime usedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<String> warnings) {

    static CsvMappingResponse from(CsvMapping mapping, List<String> warnings) {
        return new CsvMappingResponse(mapping.getAccountId(), mapping.getDelimiter(), mapping.getEncoding(),
                mapping.isHeaderRow(), mapping.getSkipRows(), mapping.getHeaderSignature(), mapping.getDateColumn(),
                mapping.getDateFormat(), mapping.getPostDateColumn(), List.copyOf(mapping.getDescriptionColumns()),
                mapping.getAmountMode(), mapping.getAmountColumn(), mapping.getDebitColumn(),
                mapping.getCreditColumn(), mapping.getSign(), mapping.getBalanceColumn(),
                mapping.getCheckNumberColumn(), mapping.getUsedAt(), mapping.getCreatedAt(), mapping.getUpdatedAt(),
                List.copyOf(warnings));
    }
}

package com.finalysis.ingestion.csv;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Builds mapping snapshots ({@link CsvMappingSpec}) for parser tests, with the sample institutions'
 * layouts as starting points. Fields are set directly; {@link #build()} computes the header
 * signature like the API.
 */
final class TestCsvMapping {

    String delimiter = ",";
    String encoding = "UTF-8";
    boolean headerRow = true;
    int skipRows;
    List<String> headers;
    int columnCount;
    String dateColumn;
    String dateFormat;
    String postDateColumn;
    List<String> descriptionColumns;
    CsvAmountMode amountMode = CsvAmountMode.SINGLE;
    String amountColumn;
    String debitColumn;
    String creditColumn;
    CsvSign sign = CsvSign.AS_IS;
    String balanceColumn;
    String checkNumberColumn;

    /** Northfield checking and savings: debit/credit columns and a running balance. */
    static TestCsvMapping northfield() {
        TestCsvMapping m = new TestCsvMapping();
        m.headers = List.of("Date", "Description", "Debit", "Credit", "Balance");
        m.dateColumn = "Date";
        m.dateFormat = "MM/dd/yyyy";
        m.descriptionColumns = List.of("Description");
        m.amountMode = CsvAmountMode.DEBIT_CREDIT;
        m.debitColumn = "Debit";
        m.creditColumn = "Credit";
        m.balanceColumn = "Balance";
        return m;
    }

    /** Apex Visa: transaction and post dates, purchases positive. */
    static TestCsvMapping apex() {
        TestCsvMapping m = new TestCsvMapping();
        m.headers = List.of("Transaction Date", "Post Date", "Description", "Category", "Amount");
        m.dateColumn = "Transaction Date";
        m.postDateColumn = "Post Date";
        m.dateFormat = "yyyy-MM-dd";
        m.descriptionColumns = List.of("Description");
        m.amountColumn = "Amount";
        m.sign = CsvSign.INVERTED;
        return m;
    }

    /** Summit card: one signed amount, purchases negative. */
    static TestCsvMapping summit() {
        TestCsvMapping m = new TestCsvMapping();
        m.headers = List.of("Trans. Date", "Merchant Name", "Amount");
        m.dateColumn = "Trans. Date";
        m.dateFormat = "dd-MMM-yyyy";
        m.descriptionColumns = List.of("Merchant Name");
        m.amountColumn = "Amount";
        return m;
    }

    /** A generic header layout {@code Date,Description,Amount} with ISO dates. */
    static TestCsvMapping simple() {
        TestCsvMapping m = new TestCsvMapping();
        m.headers = List.of("Date", "Description", "Amount");
        m.dateColumn = "Date";
        m.dateFormat = "yyyy-MM-dd";
        m.descriptionColumns = List.of("Description");
        m.amountColumn = "Amount";
        return m;
    }

    CsvMappingSpec build() {
        String signature = headerRow
                ? CsvHeaderSignature.ofHeaders(headers)
                : CsvHeaderSignature.ofColumnCount(columnCount);
        return new CsvMappingSpec(new CsvMappingValues(delimiter, encoding, headerRow, skipRows, signature,
                dateColumn, dateFormat, postDateColumn, descriptionColumns, amountMode, amountColumn, debitColumn,
                creditColumn, sign, balanceColumn, checkNumberColumn), OffsetDateTime.parse("2026-04-01T00:00:00Z"));
    }
}

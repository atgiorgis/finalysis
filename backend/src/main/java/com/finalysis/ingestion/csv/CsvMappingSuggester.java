package com.finalysis.ingestion.csv;

import com.finalysis.account.AccountType;
import com.finalysis.ingestion.csv.SuggestedCsvMapping.Confidence;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Suggests a CSV mapping from a file's header row and data rows (ING-01). Plain Java, no AI:
 * <ul>
 *   <li>Header names are matched against synonym lists, ignoring case, surrounding spaces and
 *       punctuation ({@code "Trans. Date"} is {@code trans date}). Earlier synonyms win, a header
 *       serves one role at most, and a name that appears twice is never suggested (a mapping can't
 *       reference it).</li>
 *   <li>With both a transaction date and a post date, the post date is the printed date and the
 *       transaction date is {@code txnDate}.</li>
 *   <li>The date format is the first of {@link #DATE_FORMATS} that reads every date in the rows.
 *       It is ambiguous only if the formats that read every date give different dates for some row
 *       (MM/dd and M/d agree on zero-padded dates); the US format is then chosen, with a note.</li>
 *   <li>Sign: a card account with a single amount column is INVERTED when most amounts are
 *       positive (purchases printed positive), else AS_IS. Everything else is AS_IS.</li>
 * </ul>
 * The guesses read every data row, not just the ones the preview shows.
 */
final class CsvMappingSuggester {

    static final List<String> TRANSACTION_DATE = List.of("date", "transaction date", "trans date");
    static final List<String> POST_DATE = List.of("posted date", "post date", "posting date");
    static final List<String> DESCRIPTION = List.of("description", "payee", "payee name", "merchant", "merchant name",
            "name", "memo", "details", "transaction");
    static final List<String> AMOUNT = List.of("amount", "transaction amount");
    static final List<String> DEBIT = List.of("debit", "withdrawal", "withdrawals", "money out");
    static final List<String> CREDIT = List.of("credit", "deposit", "deposits", "money in");
    static final List<String> BALANCE = List.of("balance", "running balance");
    static final List<String> CHECK_NUMBER = List.of("check", "check number", "check no");
    /** Tried in this order; the US formats come first, so they win when dates are ambiguous. */
    static final List<String> DATE_FORMATS = List.of("MM/dd/yyyy", "M/d/yyyy", "yyyy-MM-dd", "dd-MMM-yyyy", "MM/dd/yy",
            "dd/MM/yyyy");
    private static final Map<String, DateTimeFormatter> FORMATTERS = DATE_FORMATS.stream()
            .collect(Collectors.toMap(format -> format, format -> CsvDateFormat.formatter(format).orElseThrow()));

    private CsvMappingSuggester() {
    }

    /**
     * @param headers the header row, each name stripped
     * @param rows    the data rows' cells as read
     */
    static SuggestedCsvMapping suggest(List<String> headers, List<List<String>> rows, CsvTable table,
                                       AccountType accountType) {
        return new Suggestion(headers, rows, accountType).build(table);
    }

    /** Lower case, punctuation as spaces, runs of whitespace as one space, stripped. */
    static String normalize(String header) {
        return header.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s+", " ").strip();
    }

    private static final class Suggestion {

        private final List<String> headers;
        private final List<List<String>> rows;
        private final AccountType accountType;
        private final Set<String> used = new HashSet<>();
        private final Map<String, Confidence> confidence = new LinkedHashMap<>();
        private final List<String> notes = new ArrayList<>();

        Suggestion(List<String> headers, List<List<String>> rows, AccountType accountType) {
            this.headers = headers;
            // Rows with another column count fail the import anyway; they are no evidence.
            this.rows = rows.stream().filter(row -> row.size() == headers.size()).toList();
            this.accountType = accountType;
        }

        SuggestedCsvMapping build(CsvTable table) {
            String transactionDate = find(TRANSACTION_DATE);
            String postDate = find(POST_DATE);
            String dateColumn = transactionDate != null ? transactionDate : postDate;
            String postDateColumn = transactionDate != null ? postDate : null;
            confidence.put("dateColumn", dateColumn != null ? Confidence.MATCHED : Confidence.MISSING);
            String dateFormat = dateFormat(dateColumn, postDateColumn);
            if (postDateColumn != null) {
                confidence.put("postDateColumn", Confidence.MATCHED);
            }

            String description = find(DESCRIPTION);
            confidence.put("descriptionColumns", description != null ? Confidence.MATCHED : Confidence.MISSING);
            List<String> others = headers.stream()
                    .filter(h -> !used.contains(h) && DESCRIPTION.contains(normalize(h)))
                    .toList();
            if (description != null && !others.isEmpty()) {
                notes.add("descriptionColumns: also description-like, not included: " + String.join(", ", others));
            }

            String amount = find(AMOUNT);
            String debit = amount == null ? find(DEBIT) : null;
            String credit = amount == null ? find(CREDIT) : null;
            CsvAmountMode mode = null;
            if (amount != null) {
                mode = CsvAmountMode.SINGLE;
                confidence.put("amountColumn", Confidence.MATCHED);
            } else if (debit != null || credit != null) {
                mode = CsvAmountMode.DEBIT_CREDIT;
                confidence.put("debitColumn", debit != null ? Confidence.MATCHED : Confidence.MISSING);
                confidence.put("creditColumn", credit != null ? Confidence.MATCHED : Confidence.MISSING);
            } else {
                confidence.put("amountColumn", Confidence.MISSING);
            }
            CsvSign sign = sign(mode, amount);

            String balance = find(BALANCE);
            if (balance != null) {
                confidence.put("balanceColumn", Confidence.MATCHED);
            }
            String checkNumber = find(CHECK_NUMBER);
            if (checkNumber != null) {
                confidence.put("checkNumberColumn", Confidence.MATCHED);
            }
            if (table.skipRows() > 0) {
                notes.add("skipRows: the table starts on line " + (table.skipRows() + 1) + "; the lines above it are skipped");
            }

            CsvMappingRequest mapping = new CsvMappingRequest(headers, null, String.valueOf(table.delimiter()),
                    table.encoding(), true, table.skipRows(), dateColumn, dateFormat, postDateColumn,
                    description != null ? List.of(description) : List.of(), mode, amount, debit, credit, sign,
                    balance, checkNumber);
            return new SuggestedCsvMapping(mapping, confidence, notes);
        }

        /** The unused, unique header matching the earliest synonym, or null. */
        private String find(List<String> synonyms) {
            for (String synonym : synonyms) {
                for (String header : headers) {
                    if (!used.contains(header) && normalize(header).equals(synonym)
                            && Collections.frequency(headers, header) == 1) {
                        used.add(header);
                        return header;
                    }
                }
            }
            return null;
        }

        private String dateFormat(String dateColumn, String postDateColumn) {
            if (dateColumn == null) {
                confidence.put("dateFormat", Confidence.MISSING);
                return null;
            }
            List<String> dates = new ArrayList<>();
            for (String column : new String[] {dateColumn, postDateColumn}) {
                if (column != null) {
                    int index = headers.indexOf(column);
                    rows.stream().map(row -> row.get(index).strip()).filter(cell -> !cell.isEmpty()).forEach(dates::add);
                }
            }
            if (dates.isEmpty()) {
                confidence.put("dateFormat", Confidence.MISSING);
                notes.add("dateFormat: the file has no dates to recognize a format from");
                return null;
            }
            List<String> candidates = DATE_FORMATS.stream()
                    .filter(format -> dates.stream().allMatch(date -> parse(format, date) != null))
                    .toList();
            if (candidates.isEmpty()) {
                confidence.put("dateFormat", Confidence.MISSING);
                notes.add("dateFormat: none of " + String.join(", ", DATE_FORMATS) + " reads every date; set the pattern");
                return null;
            }
            String chosen = candidates.getFirst();
            List<String> disagreeing = candidates.stream()
                    .filter(format -> dates.stream().anyMatch(date -> !parse(format, date).equals(parse(chosen, date))))
                    .toList();
            if (disagreeing.isEmpty()) {
                confidence.put("dateFormat", Confidence.MATCHED);
            } else {
                confidence.put("dateFormat", Confidence.GUESSED);
                notes.add("dateFormat: ambiguous date format: the dates also read as " + String.join(", ", disagreeing)
                        + ", giving different dates; " + chosen + " (US) was chosen");
            }
            return chosen;
        }

        private CsvSign sign(CsvAmountMode mode, String amountColumn) {
            if (mode == CsvAmountMode.DEBIT_CREDIT) {
                // The columns say which way money moved.
                confidence.put("sign", Confidence.MATCHED);
                return CsvSign.AS_IS;
            }
            if (accountType != AccountType.CREDIT_CARD) {
                confidence.put("sign", Confidence.GUESSED);
                return CsvSign.AS_IS;
            }
            if (mode == null) {
                confidence.put("sign", Confidence.MISSING);
                return null;
            }
            int index = headers.indexOf(amountColumn);
            List<BigDecimal> amounts = rows.stream().map(row -> amount(row.get(index))).filter(Objects::nonNull)
                    .filter(value -> value.signum() != 0).toList();
            long positive = amounts.stream().filter(value -> value.signum() > 0).count();
            CsvSign sign = positive * 2 > amounts.size() ? CsvSign.INVERTED : CsvSign.AS_IS;
            confidence.put("sign", Confidence.GUESSED);
            notes.add("sign: " + positive + " of " + amounts.size() + " amounts are positive, so purchases look "
                    + (sign == CsvSign.INVERTED ? "positive (INVERTED)" : "negative (AS_IS)"));
            return sign;
        }

        private static BigDecimal amount(String cell) {
            try {
                return CsvAmount.parse(cell);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private static LocalDate parse(String format, String date) {
            try {
                return LocalDate.parse(date, FORMATTERS.get(format));
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }
}

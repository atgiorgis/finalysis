package com.finalysis.categorize.eval;

import com.finalysis.account.Account;
import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.categorize.eval.EvalSet.StatementLine;
import com.finalysis.ingestion.ImportOutcome;
import com.finalysis.ingestion.ImportRequest;
import com.finalysis.ingestion.StatementImportService;
import com.finalysis.ingestion.csv.CsvAmountMode;
import com.finalysis.ingestion.csv.CsvMappingRequest;
import com.finalysis.ingestion.csv.CsvMappingService;
import com.finalysis.ingestion.csv.CsvSign;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds the evaluation database the same way every time (docs/plans/phase-4.md, step 9a notes): one checking account,
 * a CSV mapping, and the set's {@link StatementLine}s imported as one CSV statement per calendar month through the
 * real import path, so merchant cleaning and the seed rules run exactly as after a user's upload. The labels are
 * never written: no USER or AI category exists afterwards, so examples can only come from RULE rows.
 *
 * <p>The database must hold no txns before seeding: the AI stage reads every uncategorized txn, so anything else
 * would be sent and scored.
 */
public class EvalSeeder {

    /** Neither phrase nor the last four occurs in any description (checked), so redaction removes nothing extra. */
    static final String ACCOUNT_NAME = "Harness Checking";
    static final String INSTITUTION = "Harness Bank";
    static final String LAST_FOUR = "0000";
    static final List<String> HEADERS = List.of("Posted On", "Details", "Signed Amount");

    private final AccountRepository accounts;
    private final CsvMappingService mappings;
    private final StatementImportService imports;
    private final JdbcTemplate jdbc;

    public EvalSeeder(AccountRepository accounts, CsvMappingService mappings, StatementImportService imports,
                      JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.mappings = mappings;
        this.imports = imports;
        this.jdbc = jdbc;
    }

    /**
     * @param txnByRow each row number's txn id
     * @param millis   import and rules time
     */
    public record Seeded(long accountId, Map<Integer, Long> txnByRow, long millis) {
    }

    public Seeded seed(List<StatementLine> lines) {
        long start = System.nanoTime();
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM txn", Integer.class);
        if (existing == null || existing != 0) {
            throw new IllegalStateException("The evaluation needs a database with no txns; found " + existing);
        }
        for (StatementLine line : lines) {
            String description = line.description().toUpperCase(Locale.ROOT);
            if (description.contains(ACCOUNT_NAME.toUpperCase(Locale.ROOT))
                    || description.contains(INSTITUTION.toUpperCase(Locale.ROOT)) || description.contains(LAST_FOUR)) {
                throw new IllegalStateException("Row " + line.number() + " contains the harness account's name, "
                        + "institution or last four; redaction would treat it differently");
            }
        }

        long accountId = accounts.save(new Account(ACCOUNT_NAME, INSTITUTION, AccountType.CHECKING, LAST_FOUR)).getId();
        mappings.put(accountId, new CsvMappingRequest(HEADERS, null, null, null, true, 0, HEADERS.get(0), "yyyy-MM-dd",
                null, List.of(HEADERS.get(1)), CsvAmountMode.SINGLE, HEADERS.get(2), null, null, CsvSign.AS_IS, null,
                null), false);

        Map<YearMonth, List<StatementLine>> byMonth = lines.stream()
                .collect(Collectors.groupingBy(line -> YearMonth.from(line.date()), TreeMap::new, Collectors.toList()));
        for (Map.Entry<YearMonth, List<StatementLine>> month : byMonth.entrySet()) {
            ImportOutcome outcome = imports.importStatement(new ImportRequest(accountId,
                    "harness-" + month.getKey() + ".csv", csv(month.getValue()), null, null, null, null));
            if (!(outcome instanceof ImportOutcome.Imported imported)
                    || imported.summary().inserted() != month.getValue().size()) {
                throw new IllegalStateException("The statement for " + month.getKey() + " did not import every row");
            }
        }

        Map<LocalDate, Integer> rowByDate = new HashMap<>();
        Map<Integer, String> descriptionByRow = new HashMap<>();
        lines.forEach(line -> {
            rowByDate.put(line.date(), line.number());
            descriptionByRow.put(line.number(), line.description());
        });
        Map<Integer, Long> txnByRow = new TreeMap<>();
        jdbc.query("SELECT id, txn_date, raw_description FROM txn WHERE account_id = ?", rs -> {
            Integer row = rowByDate.get(rs.getObject("txn_date", LocalDate.class));
            if (row == null || !descriptionByRow.get(row).equals(rs.getString("raw_description"))) {
                throw new IllegalStateException("A txn does not match its row");
            }
            txnByRow.put(row, rs.getLong("id"));
        }, accountId);
        if (txnByRow.size() != lines.size()) {
            throw new IllegalStateException("Expected " + lines.size() + " txns, found " + txnByRow.size());
        }
        Integer labeled = jdbc.queryForObject(
                "SELECT count(*) FROM txn WHERE category_source IN ('USER', 'AI')", Integer.class);
        if (labeled == null || labeled != 0) {
            throw new IllegalStateException("Seeding must leave only RULE categories; found " + labeled + " others");
        }
        return new Seeded(accountId, Map.copyOf(txnByRow), (System.nanoTime() - start) / 1_000_000);
    }

    /** One month's statement: ISO dates, descriptions quoted as RFC 4180 asks, amounts as written. */
    static byte[] csv(List<StatementLine> lines) {
        StringBuilder csv = new StringBuilder(String.join(",", HEADERS)).append('\n');
        for (StatementLine line : lines) {
            csv.append(line.date()).append(",\"").append(line.description().replace("\"", "\"\"")).append("\",")
                    .append(line.amount().toPlainString()).append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
}

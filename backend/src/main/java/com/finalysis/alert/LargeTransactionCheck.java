package com.finalysis.alert;

import com.finalysis.alert.AlertTxnQuery.Row;
import com.finalysis.analytics.Arithmetic;
import com.finalysis.analytics.CategoryRef;
import com.finalysis.analytics.MonthRange;
import com.finalysis.analytics.ReferenceQuery;
import com.finalysis.analytics.TxnBucketSql;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Unusually large transaction (docs/plans/phase-5.md §6.3, {@code large-transaction}): a charge in the {@code EXPENSE}
 * or {@code UNCATEGORIZED} bucket with {@code |amount| ≥ max(min-amount, times-median × median)}.
 * <ul>
 *   <li>The median is {@code percentile_disc(0.5)} of {@code |amount|} (exact: an actual charge) over the charges of
 *       the same bucket and category (uncategorized ones for an uncategorized charge) dated in the
 *       {@code history-days} before its day, not on it.</li>
 *   <li>Without {@code min-history} such charges there is no alert, so a first month of rent is not "unusual".</li>
 *   <li>The threshold is rounded {@code HALF_EVEN} to cents and compared with {@code |amount|} as shown (§6.6).</li>
 * </ul>
 * Keyed by the txn's fingerprint.
 */
@Component
class LargeTransactionCheck implements AlertCheck {

    /**
     * Candidates (charges of at least {@code min-amount}: below it no threshold can be reached) with their history's
     * median and size. The history is read from the same bucketed rows, so it follows the same counting rules.
     */
    static final String SQL = "WITH b AS (\n"
            + "    SELECT t.id, t.account_id, t.txn_date, t.amount, t.merchant, t.fingerprint, t.category_id,\n"
            + TxnBucketSql.BUCKET + " AS bucket\n"
            + "    FROM " + TxnBucketSql.FROM + """

                WHERE t.amount < 0 AND t.txn_date >= :historyFrom AND t.txn_date < :dayAfter
            )
            SELECT x.id, x.account_id, x.txn_date, x.amount, x.merchant, x.fingerprint, x.category_id,
                   h.median, h.history_count
            FROM b x
            CROSS JOIN LATERAL (
                SELECT percentile_disc(0.5) WITHIN GROUP (ORDER BY -y.amount) AS median, count(*) AS history_count
                FROM b y
                WHERE y.bucket = x.bucket
                  AND y.category_id IS NOT DISTINCT FROM x.category_id
                  AND y.txn_date >= x.txn_date - :historyDays
                  AND y.txn_date < x.txn_date
            ) h
            WHERE x.txn_date >= :from
              AND x.bucket IN ('EXPENSE', 'UNCATEGORIZED')
              AND -x.amount >= :minAmount
            ORDER BY x.txn_date, x.id
            """;

    private final JdbcClient jdbc;
    private final ReferenceQuery references;
    private final AlertProperties.LargeTransaction settings;

    LargeTransactionCheck(JdbcClient jdbc, ReferenceQuery references, AlertProperties properties) {
        this.jdbc = jdbc;
        this.references = references;
        this.settings = properties.largeTransaction();
    }

    @Override
    public AlertType type() {
        return AlertType.LARGE_TRANSACTION;
    }

    @Override
    public List<Finding> find(MonthRange range) {
        LocalDate from = range.from().atDay(1);
        List<Candidate> candidates = jdbc.sql(SQL)
                .param("historyFrom", from.minusDays(settings.historyDays()))
                .param("from", from)
                .param("dayAfter", range.to().plusMonths(1).atDay(1))
                .param("historyDays", settings.historyDays())
                .param("minAmount", settings.minAmount())
                .query((rs, n) -> new Candidate(AlertTxnQuery.row(rs), rs.getObject("category_id", Long.class),
                        rs.getBigDecimal("median"), rs.getInt("history_count")))
                .list();
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, CategoryRef> categories = references.categories();
        List<Finding> findings = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.historyCount() < settings.minHistory()) {
                continue;
            }
            BigDecimal observed = candidate.row().ref().amount().negate();
            BigDecimal median = candidate.median();
            BigDecimal threshold = threshold(median);
            if (observed.compareTo(threshold) < 0) {
                continue;
            }
            TxnRef txn = candidate.row().ref();
            LocalDate day = txn.txnDate();
            YearMonth month = YearMonth.from(day);
            CategoryRef category = candidate.categoryId() == null ? null : categories.get(candidate.categoryId());
            AlertBasis basis = new AlertBasis(Quantity.money(observed), Quantity.money(median),
                    Quantity.money(threshold), day.minusDays(settings.historyDays()), day.minusDays(1),
                    List.of(new AlertSetting("min-amount", Quantity.money(settings.minAmount())),
                            new AlertSetting("times-median", Quantity.ratio(settings.timesMedian())),
                            new AlertSetting("history-days", Quantity.days(settings.historyDays())),
                            new AlertSetting("min-history", Quantity.count(settings.minHistory()))));
            AlertDetails details = new AlertDetails.LargeTransaction(txn, category, median,
                    candidate.historyCount(), Arithmetic.ratio(observed, median));
            findings.add(new Finding(AlertKey.of(type(), month, List.of(candidate.row().fingerprint())), type(), month,
                    basis, details, List.of(txn), Set.of(txn.accountId()), day.minusDays(settings.historyDays()), day));
        }
        return findings;
    }

    /** {@code max(min-amount, times-median × median)}, rounded once, {@code HALF_EVEN}. */
    BigDecimal threshold(BigDecimal median) {
        return Arithmetic.money(settings.minAmount().max(settings.timesMedian().multiply(median)));
    }

    /** @param median null when the history is empty */
    private record Candidate(Row row, Long categoryId, BigDecimal median, int historyCount) {
    }
}

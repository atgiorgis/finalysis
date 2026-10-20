package com.finalysis.alert;

import com.finalysis.alert.AlertDetails.DuplicateReason;
import com.finalysis.alert.AlertTxnQuery.Row;
import com.finalysis.analytics.MonthRange;
import com.finalysis.analytics.TxnBucketSql;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Possible duplicate charge (docs/plans/phase-5.md §6.3, {@code possible-duplicate}). ING-03 keeps genuine duplicates
 * on purpose; this is the prompt to check them. Two txns are a pair when:
 * <ul>
 *   <li>both are money out and not transfers (neither transfer bucket), in the same account, with the same amount and
 *       the same merchant (both without one: the same raw description), dated at most {@code window-days} apart
 *       ({@code SAME_MERCHANT_AMOUNT_DATE}); or</li>
 *   <li>they are in the same account with the same non-null {@code external_id}, the OFX FITID safety net ING-03
 *       promised ({@code SAME_EXTERNAL_ID}).</li>
 * </ul>
 * Pairs are joined into groups (connected components), so three identical charges are one alert with three txns. A
 * group's month is that of its earliest txn, and its key hashes its sorted fingerprints: a further identical charge
 * makes a new key, which is new information. The raw description is compared in SQL only; it is never returned.
 */
@Component
class PossibleDuplicateCheck implements AlertCheck {

    static final String PAIRS_SQL = "WITH b AS (\n"
            + "    SELECT t.id, t.account_id, t.txn_date, t.amount, t.merchant, t.raw_description, t.external_id,\n"
            + TxnBucketSql.BUCKET + " AS bucket\n"
            + "    FROM " + TxnBucketSql.FROM + """

            )
            SELECT x.id AS first_id, y.id AS second_id, 'SAME_MERCHANT_AMOUNT_DATE' AS reason
            FROM b x
            JOIN b y ON y.account_id = x.account_id AND y.amount = x.amount AND y.id > x.id
            WHERE x.amount < 0
              AND x.bucket NOT IN ('LINKED_TRANSFER', 'UNLINKED_TRANSFER')
              AND y.bucket NOT IN ('LINKED_TRANSFER', 'UNLINKED_TRANSFER')
              AND (x.merchant = y.merchant
                   OR (x.merchant IS NULL AND y.merchant IS NULL AND x.raw_description = y.raw_description))
              AND abs(y.txn_date - x.txn_date) <= :windowDays
            UNION ALL
            SELECT x.id, y.id, 'SAME_EXTERNAL_ID'
            FROM b x
            JOIN b y ON y.account_id = x.account_id AND y.external_id = x.external_id AND y.id > x.id
            """;

    private final JdbcClient jdbc;
    private final AlertTxnQuery txns;
    private final AlertProperties.PossibleDuplicate settings;

    PossibleDuplicateCheck(JdbcClient jdbc, AlertTxnQuery txns, AlertProperties properties) {
        this.jdbc = jdbc;
        this.txns = txns;
        this.settings = properties.possibleDuplicate();
    }

    @Override
    public AlertType type() {
        return AlertType.POSSIBLE_DUPLICATE;
    }

    @Override
    public List<Finding> find(MonthRange range) {
        List<Pair> pairs = jdbc.sql(PAIRS_SQL)
                .param("windowDays", settings.windowDays())
                .query((rs, n) -> new Pair(rs.getLong("first_id"), rs.getLong("second_id"),
                        DuplicateReason.valueOf(rs.getString("reason"))))
                .list();
        if (pairs.isEmpty()) {
            return List.of();
        }
        Map<Long, Row> rows = txns.byId(pairs.stream().flatMap(p -> Stream.of(p.first(), p.second()))
                .distinct().toList());

        // Connected components: union-find over the pairs, then each root's members.
        Map<Long, Long> parent = new HashMap<>();
        for (Pair pair : pairs) {
            parent.put(find(parent, pair.first()), find(parent, pair.second()));
        }
        Map<Long, List<Row>> groups = new TreeMap<>();
        Map<Long, Set<DuplicateReason>> reasons = new HashMap<>();
        for (Long id : rows.keySet()) {
            groups.computeIfAbsent(find(parent, id), root -> new ArrayList<>()).add(rows.get(id));
        }
        for (Pair pair : pairs) {
            reasons.computeIfAbsent(find(parent, pair.first()), root -> EnumSet.noneOf(DuplicateReason.class))
                    .add(pair.reason());
        }

        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<Long, List<Row>> group : groups.entrySet()) {
            List<Row> members = group.getValue().stream()
                    .sorted(Comparator.comparing((Row r) -> r.ref().txnDate()).thenComparingLong(r -> r.ref().id()))
                    .toList();
            LocalDate first = members.getFirst().ref().txnDate();
            YearMonth month = YearMonth.from(first);
            if (month.isBefore(range.from()) || month.isAfter(range.to())) {
                continue;
            }
            findings.add(finding(month, members, reasons.get(group.getKey())));
        }
        return findings;
    }

    private Finding finding(YearMonth month, List<Row> members, Set<DuplicateReason> reasons) {
        LocalDate first = members.getFirst().ref().txnDate();
        LocalDate last = members.stream().map(r -> r.ref().txnDate()).max(LocalDate::compareTo).orElseThrow();
        List<String> fingerprints = members.stream().map(Row::fingerprint).sorted().toList();
        BigDecimal amount = same(members.stream().map(r -> r.ref().amount().negate()).toList());
        String merchant = same(members.stream().map(r -> r.ref().merchant()).toList());
        Set<Long> accounts = new LinkedHashSet<>();
        members.forEach(r -> accounts.add(r.ref().accountId()));

        AlertBasis basis = new AlertBasis(Quantity.count(members.size()), null, Quantity.count(2), first, last,
                List.of(new AlertSetting("window-days", Quantity.days(settings.windowDays()))));
        AlertDetails details = new AlertDetails.PossibleDuplicate(List.copyOf(reasons), amount, merchant);
        List<TxnRef> shown = members.stream().limit(AlertQueryService.MAX_TXNS).map(Row::ref).toList();
        return new Finding(AlertKey.of(type(), month, fingerprints), type(), month, basis, details, shown, accounts,
                first, last);
    }

    /** The one value every member has, or null when they differ (or it is null). */
    private static <T> T same(List<T> values) {
        T value = values.getFirst();
        return values.stream().allMatch(v -> Objects.equals(v, value)) ? value : null;
    }

    private static long find(Map<Long, Long> parent, long id) {
        long root = id;
        while (parent.containsKey(root) && parent.get(root) != root) {
            root = parent.get(root);
        }
        // Path compression keeps long chains (a charge repeated daily) cheap.
        long node = id;
        while (node != root) {
            long next = parent.get(node);
            parent.put(node, root);
            node = next;
        }
        return root;
    }

    private record Pair(long first, long second, DuplicateReason reason) {
    }
}

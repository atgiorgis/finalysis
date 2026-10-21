package com.finalysis.chat.summary;

import com.finalysis.chat.tool.view.AlertsView;
import com.finalysis.chat.tool.view.FlagView;
import com.finalysis.chat.tool.view.MonthTotalsView;
import com.finalysis.chat.tool.view.MonthlyRecordsView;
import com.finalysis.chat.tool.view.RecurringView;
import com.finalysis.chat.tool.view.TrendsView;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The monthly summary without a model (ANA-05, docs/plans/phase-6.md §11.1): fixed sentences filled from the same checked
 * records the model would get, with their refs as markers. It is the answer with AI off, and the fallback when the
 * model's summary stays ungrounded. Every figure is a value of the records as returned (money formatted with thousands
 * separators, money out as a magnitude beside a word that says so), so the grounding check passes it by construction;
 * Java only chooses which rows to mention (the biggest changes against the trailing average) and never shows a number it
 * computed.
 */
public final class MonthlySummaryTemplate {

    /** At most this many category changes, alerts and recurring charges are named. */
    static final int MAX_ITEMS = 3;

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US);

    private MonthlySummaryTemplate() {
    }

    public static String render(MonthlyRecordsView records) {
        String month = YearMonth.parse(records.month()).format(MONTH);
        MonthTotalsView.Month totals = records.totals().months().stream()
                .filter(m -> records.month().equals(m.month())).findFirst().orElse(null);
        if (totals == null || "NONE".equals(totals.coverage())) {
            return "There is no statement data for " + month + ".";
        }
        List<String> sentences = new ArrayList<>();
        sentences.add(totals(month, totals));
        String changes = changes(records.trends());
        if (changes != null) {
            sentences.add(changes);
        }
        String alerts = alerts(records.alerts());
        if (alerts != null) {
            sentences.add(alerts);
        }
        String recurring = recurring(records.recurring());
        if (recurring != null) {
            sentences.add(recurring);
        }
        sentences.addAll(caveats(totals));
        return String.join(" ", sentences);
    }

    private static String totals(String month, MonthTotalsView.Month m) {
        StringBuilder s = new StringBuilder("In ").append(month).append(" you spent ").append(magnitude(m.spending()))
                .append(" and received ").append(signed(m.income())).append(", a net of ").append(signed(m.net()));
        if (m.savingsRatePercent() != null) {
            s.append(" and a savings rate of ").append(m.savingsRatePercent()).append('%');
        }
        return s.append(marker(m.ref())).append('.').toString();
    }

    /** The categories whose spending moved most against their trailing 3-month average, largest move first. */
    private static String changes(TrendsView trends) {
        if (trends == null) {
            return null;
        }
        List<TrendsView.Row> moved = trends.rows().stream()
                .filter(r -> r.category() != null && "SPENT".equals(r.measure()) && r.amount() != null
                        && r.trailing3() != null && r.trailing3().amount() != null
                        && new BigDecimal(r.amount()).compareTo(new BigDecimal(r.trailing3().amount())) != 0)
                .sorted(Comparator.comparing((TrendsView.Row r) -> new BigDecimal(r.amount())
                                .subtract(new BigDecimal(r.trailing3().amount())).abs()).reversed()
                        .thenComparing(TrendsView.Row::category))
                .limit(MAX_ITEMS).toList();
        if (moved.isEmpty()) {
            return null;
        }
        return "Against the average of the complete months before, the biggest changes were " + moved.stream()
                .map(r -> r.category() + " at " + signed(r.amount())
                        + (new BigDecimal(r.amount()).compareTo(new BigDecimal(r.trailing3().amount())) > 0
                        ? ", up from " : ", down from ") + signed(r.trailing3().amount()) + marker(r.ref()))
                .collect(Collectors.joining("; ")) + ".";
    }

    private static String alerts(AlertsView alerts) {
        if (alerts == null || alerts.items().isEmpty()) {
            return null;
        }
        List<String> named = alerts.items().stream().limit(MAX_ITEMS).map(a -> label(a.type())
                + (a.details() == null ? "" : a.details().category() != null ? " in " + a.details().category()
                : a.details().merchant() != null ? " at " + a.details().merchant() : "") + marker(a.ref())).toList();
        String more = alerts.items().size() > MAX_ITEMS || Boolean.TRUE.equals(alerts.truncated())
                ? ", and more" : "";
        return "Alerts this month: " + String.join("; ", named) + more + ".";
    }

    private static String recurring(RecurringView recurring) {
        if (recurring == null || recurring.items().isEmpty()) {
            return null;
        }
        String more = recurring.items().size() > MAX_ITEMS ? ", and more" : "";
        return "Recurring charges this month: " + recurring.items().stream().limit(MAX_ITEMS)
                .map(s -> s.merchant() + " " + magnitude(s.lastAmount()) + marker(s.ref()))
                .collect(Collectors.joining(", ")) + more + ".";
    }

    /** What the reader should know about the data behind the month (ANA-08's flags and pending reviews). */
    private static List<String> caveats(MonthTotalsView.Month m) {
        List<String> caveats = new ArrayList<>();
        List<String> flags = m.dataQuality() == null ? List.of()
                : m.dataQuality().flags().stream().map(FlagView::type).distinct().toList();
        if ("PARTIAL".equals(m.coverage()) || flags.contains("PARTIAL_COVERAGE")) {
            caveats.add("Not every account's statements cover the whole month yet, so these totals may change.");
        }
        if (flags.stream().anyMatch(f -> f.contains("RECONCILIATION"))) {
            caveats.add("A statement for this month didn't reconcile or wasn't checked, so its totals may be off.");
        }
        if (m.reviewPending() != null && m.reviewPending() > 0) {
            caveats.add(m.reviewPending() == 1 ? "One transaction is waiting for review" + marker(m.ref()) + "."
                    : m.reviewPending() + " transactions are waiting for review" + marker(m.ref()) + ".");
        }
        return caveats;
    }

    private static String marker(String ref) {
        return ref == null ? "" : " [" + ref + "]";
    }

    /** {@code CATEGORY_SPIKE} → "category spike". */
    private static String label(String constant) {
        return constant == null ? "alert" : constant.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String signed(String money) {
        return money == null ? "n/a" : format(new BigDecimal(money));
    }

    private static String magnitude(String money) {
        return money == null ? "n/a" : format(new BigDecimal(money).abs());
    }

    private static String format(BigDecimal value) {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US)).format(value);
    }
}

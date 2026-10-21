package com.finalysis.chat.grounding;

import com.finalysis.chat.ToolStatus;
import com.finalysis.chat.tool.view.AccountTotalsView;
import com.finalysis.chat.tool.view.AlertsView;
import com.finalysis.chat.tool.view.CategoryTotalsView;
import com.finalysis.chat.tool.view.ComparisonView;
import com.finalysis.chat.tool.view.CoverageView;
import com.finalysis.chat.tool.view.MerchantTotalsView;
import com.finalysis.chat.tool.view.MonthTotalsView;
import com.finalysis.chat.tool.view.RecurringView;
import com.finalysis.chat.tool.view.SearchView;
import com.finalysis.chat.tool.view.TopMerchantsView;
import com.finalysis.chat.tool.view.TransactionsView;
import com.finalysis.chat.tool.view.TrendsView;
import com.finalysis.chat.tool.view.TxnView;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The safe answer when figures stay ungrounded (docs/plans/phase-6.md §5.5, §18 decision 4): Java renders one line per
 * row of each successful tool result, from the checked views the model was sent, with their refs as markers so the
 * citations follow. Rendering from the outbound views (aliases, outbound merchant texts) keeps the fallback exactly as
 * safe as the payloads, should it ever be shown to a model as history. Every figure is a value of a result, formatted
 * with thousands separators; money out is shown as a magnitude beside its word ("spending 2,345.67"), net keeps its
 * sign. No model is involved and nothing is computed.
 */
public final class FallbackAnswer {

    static final String HEADER = "I couldn't confirm every figure in my answer, so here is what the data returned:";
    static final String NOTHING = "I can't answer that from your data.";
    /** At most this many lines, then one line saying how many were left out. */
    static final int MAX_LINES = 20;

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US);

    private FallbackAnswer() {
    }

    public static String render(TurnEvidence evidence) {
        List<String> lines = new ArrayList<>();
        for (TurnEvidence.Result r : evidence.results()) {
            if (r.outcome().status() == ToolStatus.OK && r.outcome().view() != null) {
                lines.addAll(lines(r.outcome().view()));
            }
        }
        if (lines.isEmpty()) {
            return NOTHING;
        }
        List<String> shown = new ArrayList<>(lines.subList(0, Math.min(MAX_LINES, lines.size())));
        if (lines.size() > MAX_LINES) {
            shown.add("(" + (lines.size() - MAX_LINES) + " more rows not shown)");
        }
        return HEADER + "\n" + shown.stream().map(l -> "- " + l).collect(Collectors.joining("\n"));
    }

    static List<String> lines(Record view) {
        return switch (view) {
            case MonthTotalsView v -> v.months().stream().map(m -> monthLine(m, v.account())).toList();
            case AccountTotalsView v -> v.accounts().stream()
                    .flatMap(a -> a.months().stream().map(m -> monthLine(m, a.account()))).toList();
            case CategoryTotalsView v -> v.rows().stream().map(row -> join(
                    row.category() != null ? row.category() : label(row.bucket()), span(v.from(), v.to()),
                    v.account(), "money out " + magnitude(row.total().moneyOut()),
                    "money in " + magnitude(row.total().moneyIn()), count(row.total().count()))
                    + marker(row.ref())).toList();
            case TopMerchantsView v -> {
                List<String> lines = new ArrayList<>(v.items().stream().map(m -> join(
                        m.merchant() == null ? "(no merchant)" : m.merchant(), "spent " + signed(m.spent()),
                        count(m.count())) + marker(m.ref())).toList());
                lines.add(join("All merchants", span(v.from(), v.to()), "spent " + signed(v.totalSpent()),
                        count(v.totalCount())));
                yield lines;
            }
            case TrendsView v -> v.rows().stream().map(row -> join(
                    row.category() != null ? row.category() : label(row.line()), month(v.month()), v.account(),
                    ("SPENT".equals(row.measure()) ? "spent " : "received ") + signed(row.amount()),
                    row.previousMonth() == null ? null : "previous month " + signed(row.previousMonth()),
                    row.changeAmount() == null ? null : "change " + signed(row.changeAmount())
                            + (row.changePercent() == null ? "" : " (" + row.changePercent() + "%)"))
                    + marker(row.ref())).toList();
            case ComparisonView v -> List.of(join(label(v.measure()) + (v.category() == null ? "" : " " + v.category()),
                    v.account(), span(v.a().from(), v.a().to()) + " " + signed(v.a().value()),
                    span(v.b().from(), v.b().to()) + " " + signed(v.b().value()),
                    "difference " + signed(v.difference())
                            + (v.changePercent() == null ? "" : " (" + v.changePercent() + "%)"))
                    + marker(v.ref()));
            case TransactionsView v -> {
                List<String> lines = new ArrayList<>();
                lines.add(join("Transactions", span(v.from(), v.to()),
                        "money out " + magnitude(v.total().moneyOut()), "money in " + magnitude(v.total().moneyIn()),
                        count(v.total().count())) + marker(v.ref()));
                v.items().stream().map(FallbackAnswer::txnLine).forEach(lines::add);
                yield lines;
            }
            case RecurringView v -> v.items().stream().map(s -> join(s.merchant(), s.account(),
                    "typical " + signed(s.typicalAmount()), "last " + signed(s.lastAmount()),
                    s.occurrences() + " charges", s.nextExpectedDate() == null ? null : "next " + s.nextExpectedDate())
                    + marker(s.ref())).toList();
            case AlertsView v -> v.items().stream().map(a -> join(label(a.type()), month(a.month()),
                    a.details() == null ? null : a.details().category() != null ? a.details().category()
                            : a.details().merchant(),
                    a.basis() == null || a.basis().observed() == null ? null
                            : "observed " + quantity(a.basis().observed()),
                    a.basis() == null || a.basis().threshold() == null ? null
                            : "threshold " + quantity(a.basis().threshold()))
                    + marker(a.ref())).toList();
            case CoverageView v -> v.accounts().stream().map(a -> join(a.account(),
                    "covered " + a.covered().stream().map(s -> s.start() + " to " + s.end())
                            .collect(Collectors.joining(", ")),
                    a.gaps().isEmpty() ? "no gaps" : a.gaps().size() == 1 ? "1 gap" : a.gaps().size() + " gaps"))
                    .toList();
            case SearchView v -> v.candidates().stream().map(c -> join(c.merchant(), c.category(),
                    span(v.from(), v.to()), "spent " + signed(c.spent()), count(c.count())) + marker(c.ref())).toList();
            case MerchantTotalsView v -> {
                List<String> lines = new ArrayList<>(v.merchants().stream().map(m -> join(m.merchant(),
                        m.category(), span(v.from(), v.to()), v.account(), "spent " + signed(m.spent()),
                        count(m.count()))
                        + marker(m.ref())).toList());
                lines.add(join("These merchants together", span(v.from(), v.to()), v.account(),
                        "spent " + signed(v.combined().spent()), count(v.combined().count()))
                        + marker(v.combined().ref()));
                yield lines;
            }
            default -> List.of();
        };
    }

    private static String monthLine(MonthTotalsView.Month m, String account) {
        return join(month(m.month()), account, "income " + signed(m.income()), "spending " + magnitude(m.spending()),
                "net " + signed(m.net())) + marker(m.ref());
    }

    private static String txnLine(TxnView t) {
        return join(t.date(), t.merchant() == null ? "(no merchant)" : t.merchant(), signed(t.amount()), t.account())
                + marker(t.ref());
    }

    private static String quantity(AlertsView.Quantity q) {
        return switch (q.unit()) {
            case "MONEY" -> signed(q.value());
            case "PERCENT" -> q.value() + "%";
            case "DAYS" -> q.value() + " days";
            default -> q.value();
        };
    }

    /** Parts joined with a middle dot; null parts left out. */
    private static String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                out.append(out.isEmpty() ? "" : " · ").append(part);
            }
        }
        return out.toString();
    }

    private static String marker(String ref) {
        return ref == null ? "" : " [" + ref + "]";
    }

    private static String month(String month) {
        return month == null ? null : YearMonth.parse(month).format(MONTH);
    }

    private static String span(String from, String to) {
        if (from == null) {
            return null;
        }
        return from.equals(to) || to == null ? month(from) : from + " to " + to;
    }

    private static String count(Integer count) {
        return count == null ? null : count == 1 ? "1 transaction" : count + " transactions";
    }

    /** {@code SPENDING} → "Spending", {@code TOTAL_INCOME} → "Total income". */
    private static String label(String constant) {
        if (constant == null) {
            return null;
        }
        String words = constant.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** A money value as returned, with separators. */
    private static String signed(String money) {
        return money == null ? "n/a" : format(new BigDecimal(money));
    }

    /** A money value without its sign (money out beside a word that says so). */
    private static String magnitude(String money) {
        return money == null ? "n/a" : format(new BigDecimal(money).abs());
    }

    private static String format(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        return format.format(value);
    }
}

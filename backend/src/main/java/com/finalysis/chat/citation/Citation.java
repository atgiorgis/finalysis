package com.finalysis.chat.citation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.finalysis.analytics.MoneyFlow;
import com.finalysis.api.ApiMoney;
import com.finalysis.chat.tool.FigureBasis;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * One citation of an answer (CHT-05, docs/plans/phase-6.md §6), resolved locally and returned to the client only:
 * none of it is ever sent to the model (it holds ids, account names and statement lines). {@code marker} is the
 * number the answer's text shows ({@code [1]}).
 */
public sealed interface Citation {

    enum Kind { TRANSACTION, TOTAL, ALERT, SERIES }

    int marker();

    @JsonProperty("kind")
    Kind kind();

    /** A transaction, with its statement and the line it was imported from. */
    record Txn(int marker, CitedTxn txn) implements Citation {

        @Override
        public Kind kind() {
            return Kind.TRANSACTION;
        }
    }

    /**
     * A total: its label, the figures the answer quoted from it with what each rests on, and the cell's own
     * drill-downs (what a marker alone cites).
     */
    record Total(int marker, String label, List<CitedFigure> figures, List<DrillDown> drillDowns)
            implements Citation {

        public Total {
            figures = List.copyOf(figures);
            drillDowns = List.copyOf(drillDowns);
        }

        @Override
        public Kind kind() {
            return Kind.TOTAL;
        }
    }

    /** An alert by its key, with the txns it sent (at most 5). */
    record Alert(int marker, String alertKey, String type, YearMonth month, List<CitedTxn> txns) implements Citation {

        public Alert {
            txns = List.copyOf(txns);
        }

        @Override
        public Kind kind() {
            return Kind.ALERT;
        }
    }

    /** A recurring series: its account, stored merchant and the drill-down over its charges' months. */
    record Series(int marker, long accountId, String merchant, DrillDown drillDown) implements Citation {

        @Override
        public Kind kind() {
            return Kind.SERIES;
        }
    }

    /**
     * One transaction as stored now. {@code GONE} when it no longer exists (its statement was deleted): then only the
     * fingerprint is left, never stale text.
     *
     * @param sourceLine the statement line as printed (DAT-07); raw statement text, local only, never logged
     */
    record CitedTxn(Status status, String fingerprint, Long id, Long accountId, String accountName,
                    LocalDate txnDate, LocalDate postDate, @ApiMoney BigDecimal amount, String merchant,
                    String category, Statement statement, SourceLine sourceLine) {

        public enum Status { FOUND, GONE }

        public static CitedTxn gone(String fingerprint) {
            return new CitedTxn(Status.GONE, fingerprint, null, null, null, null, null, null, null, null, null, null);
        }
    }

    record Statement(long id, LocalDate periodStart, LocalDate periodEnd) {
    }

    record SourceLine(Integer number, String text) {
    }

    /**
     * The rows behind a total: {@code GET /api/analytics/transactions} with these exact parameters lists them (UI-04),
     * and {@code total} is over all of them. Up to 5 sample txns, newest first.
     */
    record DrillDown(String path, Map<String, String> params, MoneyFlow total, List<CitedTxn> sampleTxns) {

        public static final String PATH = "/api/analytics/transactions";

        public DrillDown {
            params = Map.copyOf(params);
            sampleTxns = List.copyOf(sampleTxns);
        }

        @JsonProperty("count")
        public int count() {
            return total.count();
        }
    }

    /**
     * A figure the answer quoted, as the result returned it, and what it rests on: for {@code SUM}, the value equals
     * the sum over the terms of {@code sign × component} of each drill-down's total; for {@code DERIVED}, the terms
     * are its inputs.
     */
    record CitedFigure(String field, String value, FigureBasis.Relation relation, FigureBasis.Component component,
                       List<Term> terms) {

        public CitedFigure {
            terms = List.copyOf(terms);
        }
    }

    record Term(int sign, DrillDown drillDown) {
    }
}

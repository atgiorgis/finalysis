package com.finalysis.chat.summary;

import com.finalysis.ai.AiPrices;
import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import com.finalysis.chat.ChatConversationStore;
import com.finalysis.chat.ChatModelClient;
import com.finalysis.chat.ChatProblem;
import com.finalysis.chat.ChatProperties;
import com.finalysis.chat.ChatTurnResponse;
import com.finalysis.chat.ToolCall;
import com.finalysis.chat.ToolOutcome;
import com.finalysis.chat.ToolStatus;
import com.finalysis.chat.UsageKind;
import com.finalysis.chat.UsageTally;
import com.finalysis.chat.citation.CitationResolver;
import com.finalysis.chat.grounding.AnswerCheck;
import com.finalysis.chat.grounding.AnswerGrounding;
import com.finalysis.chat.grounding.Figure;
import com.finalysis.chat.grounding.GroundingStatus;
import com.finalysis.chat.grounding.TurnEvidence;
import com.finalysis.chat.outbound.ChatOutbound;
import com.finalysis.chat.outbound.OutboundProjector;
import com.finalysis.chat.outbound.OutboundTurn;
import com.finalysis.chat.outbound.OutboundTurnFactory;
import com.finalysis.chat.outbound.ToolResultWriter;
import com.finalysis.chat.tool.CategoryNamesQuery;
import com.finalysis.chat.tool.MonthlyRecordsReader;
import com.finalysis.chat.tool.view.AlertsView;
import com.finalysis.chat.tool.view.MonthTotalsView;
import com.finalysis.chat.tool.view.MonthlyRecordsView;
import com.finalysis.chat.tool.view.RecurringView;
import com.finalysis.chat.tool.view.TrendsView;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

/**
 * The monthly summary (ANA-05, docs/plans/phase-6.md §11.1).
 * <ol>
 *   <li>The month ({@code yyyy-MM}, default the latest complete month); its Phase 5 records
 *       ({@link MonthlyRecordsReader}), projected exactly like the tools' results and checked by the guard as one
 *       {@link MonthlyRecordsView}.</li>
 *   <li>AI off, or a month without data: the {@link MonthlySummaryTemplate}, no call, nothing counted (200,
 *       {@code TEMPLATE}).</li>
 *   <li>AI on: the daily cap (429), then one call with the main role (no tools, at most {@value #MAX_OUTPUT_TOKENS}
 *       output tokens, {@code role=summary}), the records in {@code <records>}. The text is checked by the same grounding
 *       as a chat answer, against the records as sent; ungrounded or cut: one retry; still not: the template. A turn's
 *       limits apply (input per call, time, cost with the call's worst case); a limit gives the template. A failed call
 *       is a 502. The calls' usage goes to the ledger, so the daily cap counts them.</li>
 * </ol>
 * The shown text, the template's included, goes through the grounding check, and its citations are resolved locally.
 * Nothing else is stored. One count-only log line.
 */
@Service
public class MonthlySummaryService {

    static final int MAX_OUTPUT_TOKENS = 500;
    static final String RECORDS_TOOL = "monthly_summary";
    /** The AI-06 line's items: totals, trends, alerts and recurring charges. */
    static final int RECORD_SETS = 4;
    static final String RESOURCE = "chat/monthly-summary-prompt.txt";
    static final String CUT_RETRY = "Your summary was cut off at the length limit. Write it again, shorter, using only "
            + "figures from the records.";

    private static final Logger log = LoggerFactory.getLogger(MonthlySummaryService.class);
    private static final Pattern MONTH = Pattern.compile("\\d{4}-\\d{2}");

    private final MonthlyRecordsReader reader;
    private final OutboundTurnFactory turns;
    private final OutboundProjector projector;
    private final ChatOutbound outbound;
    private final ToolResultWriter writer;
    private final AnswerGrounding grounding;
    private final CitationResolver citations;
    private final ChatModelClient client;
    private final ChatConversationStore store;
    private final ChatProperties limits;
    private final AiPrices prices;
    private final Clock clock;
    private final String prompt;

    public MonthlySummaryService(MonthlyRecordsReader reader, OutboundTurnFactory turns, OutboundProjector projector,
                                 ChatOutbound outbound, ToolResultWriter writer, AnswerGrounding grounding,
                                 CitationResolver citations, ChatModelClient client, ChatConversationStore store,
                                 ChatProperties limits, AiPrices prices, Clock clock) {
        this.reader = reader;
        this.turns = turns;
        this.projector = projector;
        this.outbound = outbound;
        this.writer = writer;
        this.grounding = grounding;
        this.citations = citations;
        this.client = client;
        this.store = store;
        this.limits = limits;
        this.prices = prices;
        this.clock = clock;
        try (InputStream in = MonthlySummaryService.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing");
            }
            this.prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What was sent (or would have been), and what the shown text is checked against. */
    private record Sent(MonthlyRecordsReader.Records records, MonthlyRecordsView view, String json,
                        TurnEvidence evidence, OutboundTurn turn) {
    }

    /**
     * @param month {@code yyyy-MM}, or empty for the latest complete month
     * @throws InvalidRequestException for a month that isn't {@code yyyy-MM}
     */
    public MonthlySummaryResponse summarize(Optional<String> month) {
        long started = clock.millis();
        Optional<YearMonth> asked = month.map(MonthlySummaryService::parse);
        Optional<YearMonth> resolved = asked.isPresent() ? asked : reader.defaultMonth();
        if (resolved.isEmpty()) {
            return new MonthlySummaryResponse(null, MonthlySummaryResponse.Source.TEMPLATE,
                    "There is no statement data yet.", new ChatTurnResponse.Grounding(GroundingStatus.NO_FIGURES, 0,
                    false, 0), List.of(), List.of(), null);
        }
        Sent sent = records(resolved.get());
        if (!client.enabled() || !sent.records().hasData()) {
            return template(sent, false, null, null, started);
        }

        ZonedDateTime now = ZonedDateTime.now(clock);
        if (limits.maxDailyCost().signum() > 0) {
            ZonedDateTime midnight = now.toLocalDate().atStartOfDay(now.getZone());
            if (store.costSince(midnight.toOffsetDateTime()).compareTo(limits.maxDailyCost()) >= 0) {
                throw ChatProblem.budget(midnight.plusDays(1).toOffsetDateTime());
            }
        }

        UsageTally usage = new UsageTally(prices.of(client.modelId()));
        List<Message> messages = new ArrayList<>(List.of(new SystemMessage(prompt),
                new UserMessage("<records>" + sent.json() + "</records>")));
        boolean retried = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            int estimate = estimate(messages);
            if (!withinLimits(estimate, usage, started)) {
                return template(sent, retried, usage, "LIMIT", started);
            }
            ChatModelClient.Reply reply;
            try {
                reply = client.complete(List.copyOf(messages), MAX_OUTPUT_TOKENS, ChatModelClient.MONTHLY_ROLE,
                        RECORD_SETS);
            } catch (RuntimeException e) {
                record(usage);
                log.warn("chat monthly summary month={} outcome=ERROR error={}", sent.records().month(),
                        e.getClass().getSimpleName());
                throw ChatProblem.aiUnavailable();
            }
            usage.add(reply, estimate, MAX_OUTPUT_TOKENS);
            if (reply.text().isBlank()) {
                return template(sent, retried, usage, null, started);
            }
            AnswerCheck check = reply.cut() ? null : grounding.check(reply.text(), sent.evidence());
            if (check != null && check.grounded()) {
                return answered(sent, check, retried, usage, started);
            }
            if (attempt == 0) {
                retried = true;
                messages.add(reply.message());
                messages.add(new UserMessage(check == null ? CUT_RETRY : retryMessage(check)));
            }
        }
        return template(sent, true, usage, null, started);
    }

    private static YearMonth parse(String month) {
        if (month == null || !MONTH.matcher(month).matches()) {
            throw invalidMonth();
        }
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw invalidMonth();
        }
    }

    private static InvalidRequestException invalidMonth() {
        return new InvalidRequestException(List.of(new InvalidField("month", "must be a month, yyyy-MM")));
    }

    /** The records, projected like the tools' results and checked as one view; the evidence the text is checked on. */
    private Sent records(YearMonth month) {
        MonthlyRecordsReader.Records records = reader.read(month);
        OutboundTurn turn = turns.start();
        int withheld = turn.withheldMerchants();
        int refused = turn.refusedMerchants();
        MonthlyRecordsView view = new MonthlyRecordsView(month.toString(),
                (MonthTotalsView) projector.project(records.totals(), turn),
                (TrendsView) projector.project(records.trends(), turn),
                (AlertsView) projector.project(records.alerts(), turn),
                (RecurringView) projector.project(records.recurring(), turn));
        var checked = outbound.send(RECORDS_TOOL, view, turn, withheld, refused);
        ToolResultWriter.Written written = writer.records(checked);
        ToolOutcome outcome = new ToolOutcome(null, ToolStatus.OK, written.json(), checked.view(), checked.rowsKept(),
                0, records.notes(), written.shortened());
        List<String> names = new ArrayList<>(CategoryNamesQuery.sorted(turn.categories()));
        turn.aliases().entries().forEach(e -> names.add(e.alias()));
        TurnEvidence evidence = new TurnEvidence("", List.of(new TurnEvidence.Result(new ToolCall(RECORDS_TOOL, "{}"),
                outcome)), turn.refs(), names);
        return new Sent(records, checked.view(), written.json(), evidence, turn);
    }

    private int estimate(List<Message> messages) {
        int chars = messages.stream().mapToInt(m -> m.getText() == null ? 0 : m.getText().length()).sum();
        return (chars + 2) / 3 + 5 * messages.size();
    }

    /** The limits of a chat turn, for one more call of this estimate. */
    private boolean withinLimits(int estimate, UsageTally usage, long started) {
        if (clock.millis() - started >= limits.maxTurnTime().toMillis()
                || estimate > limits.maxInputTokensPerCall()) {
            return false;
        }
        return limits.maxTurnCost().signum() == 0 || usage.cost() == null
                || usage.cost().add(usage.worstCase(estimate, MAX_OUTPUT_TOKENS)).compareTo(limits.maxTurnCost()) <= 0;
    }

    private static String retryMessage(AnswerCheck check) {
        return "These figures are not in the records: " + check.ungrounded().stream().map(Figure::asWritten)
                .distinct().collect(Collectors.joining(", ")) + ". Write the summary again using only figures from "
                + "the records.";
    }

    private MonthlySummaryResponse answered(Sent sent, AnswerCheck check, boolean retried, UsageTally usage,
                                            long started) {
        record(usage);
        CitationResolver.CitedAnswer cited = citations.cite(check, sent.turn().refs());
        return respond(sent, MonthlySummaryResponse.Source.MODEL, cited, check, retried, usage, null, started);
    }

    private MonthlySummaryResponse template(Sent sent, boolean retried, UsageTally usage, String limit,
                                            long started) {
        record(usage);
        String text = MonthlySummaryTemplate.render(sent.view());
        AnswerCheck check = grounding.check(text, sent.evidence());
        if (!check.grounded()) {
            log.warn("chat monthly summary template ungrounded figures={}", check.ungrounded().size());
        }
        CitationResolver.CitedAnswer cited = citations.cite(check, sent.turn().refs());
        return respond(sent, MonthlySummaryResponse.Source.TEMPLATE, cited, check, retried, usage, limit, started);
    }

    private MonthlySummaryResponse respond(Sent sent, MonthlySummaryResponse.Source source,
                                           CitationResolver.CitedAnswer cited, AnswerCheck check, boolean retried,
                                           UsageTally usage, String limit, long started) {
        log.info("chat monthly summary month={} source={} grounding={} retried={} figures={} calls={} limit={} ms={}",
                sent.records().month(), source, check.status(), retried, check.figuresChecked(),
                usage == null ? 0 : usage.calls(), limit, Math.max(0, clock.millis() - started));
        return new MonthlySummaryResponse(sent.records().month().toString(), source, cited.text(),
                new ChatTurnResponse.Grounding(check.status(), check.figuresChecked(), retried,
                        check.ungrounded().size()),
                cited.citations(), sent.records().notes(),
                usage == null || usage.calls() == 0 ? null : new ChatTurnResponse.Usage(client.modelId(),
                        usage.calls(), usage.inputTokens(), usage.outputTokens(), usage.callsWithoutUsage(),
                        money(usage.cost())));
    }

    private void record(UsageTally usage) {
        if (usage != null && usage.calls() > 0) {
            store.recordUsage(usage.toUsage(UsageKind.MONTHLY_SUMMARY, client.modelId(),
                    OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)));
        }
    }

    private static String money(BigDecimal cost) {
        return cost == null ? null : cost.setScale(6, RoundingMode.HALF_EVEN).toPlainString();
    }
}

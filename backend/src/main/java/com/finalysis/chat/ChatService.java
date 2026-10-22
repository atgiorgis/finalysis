package com.finalysis.chat;

import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import com.finalysis.api.NotFoundException;
import com.finalysis.chat.citation.Citation;
import com.finalysis.chat.citation.CitationResolver;
import com.finalysis.chat.grounding.AnswerCheck;
import com.finalysis.chat.grounding.GroundingStatus;
import com.finalysis.chat.grounding.TurnEvidence;
import com.finalysis.chat.outbound.ChatPayloadGuard;
import com.finalysis.chat.outbound.ContextBlockFactory;
import com.finalysis.chat.outbound.OutboundTurn;
import com.finalysis.chat.outbound.OutboundTurnFactory;
import com.finalysis.chat.outbound.QuestionRedactor;
import com.finalysis.chat.tool.CategoryNamesQuery;
import com.finalysis.chat.tool.DataNote;
import com.finalysis.chat.tool.view.ContextBlockView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The chat API's work (docs/plans/phase-6.md §1, §10): conversations, and one turn from question to stored answer.
 * <ol>
 *   <li>AI off: 503, before anything is read, stored or sent. An unknown conversation: 404. Another turn of the same
 *       conversation running: 409; more than {@value #MAX_CONCURRENT} turns overall: a wait of up to 5 seconds, then
 *       429. The day's estimated cost at the cap: 429, before any call.</li>
 *   <li>The outbound question ({@link QuestionRedactor}); a question that is nothing once identifiers are removed is a
 *       400, and nothing is sent. The context block, checked by the guard, with today from the {@link Clock}. The
 *       conversation's history ({@link ConversationMemory#window}): its summary and recent turns, figures stripped.</li>
 *   <li>The rounds ({@link TurnLoop}), then citations resolved locally, then the turn stored
 *       ({@link ChatConversationStore}) with its usage, whatever path it took. A failed model call is stored as
 *       {@code ERROR} (exception class only) and answered 502.</li>
 *   <li>Older turns folded into the summary when due ({@link ConversationMemory#foldIfDue}), within what is left of
 *       the turn's limits, still under the conversation's one-turn-at-a-time lock, so the next turn sees it.</li>
 * </ol>
 * One count-only log line per turn: {@code chat turn conversation=7 turn=3 source=MODEL grounding=GROUNDED ...}.
 */
@Service
public class ChatService {

    static final int MAX_CONCURRENT = 2;
    static final long WAIT_SECONDS = 5;
    static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final TypeReference<List<Citation>> CITATIONS = new TypeReference<>() {
    };

    private final ChatModelClient client;
    private final ChatConversationStore store;
    private final OutboundTurnFactory turns;
    private final QuestionRedactor redactor;
    private final ContextBlockFactory context;
    private final ChatPayloadGuard guard;
    private final ChatPrompt prompt;
    private final TurnLoop loop;
    private final ConversationMemory memory;
    private final CitationResolver citations;
    private final ChatProperties limits;
    private final Clock clock;
    private final JsonMapper json;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final Semaphore overall = new Semaphore(MAX_CONCURRENT, true);

    public ChatService(ChatModelClient client, ChatConversationStore store, OutboundTurnFactory turns,
                       QuestionRedactor redactor, ContextBlockFactory context, ChatPayloadGuard guard, ChatPrompt prompt,
                       TurnLoop loop, ConversationMemory memory, CitationResolver citations, ChatProperties limits,
                       Clock clock, JsonMapper json) {
        this.client = client;
        this.store = store;
        this.turns = turns;
        this.redactor = redactor;
        this.context = context;
        this.guard = guard;
        this.prompt = prompt;
        this.loop = loop;
        this.memory = memory;
        this.citations = citations;
        this.limits = limits;
        this.clock = clock;
        this.json = json;
    }

    // ------------------------------------------------------------------ conversations

    public ConversationResponses.Created create() {
        ChatConversationStore.Created created = store.create(now());
        return new ConversationResponses.Created(created.id(), created.createdAt());
    }

    public ConversationResponses.Page list(int page, int size) {
        List<InvalidField> errors = new ArrayList<>();
        if (page < 0) {
            errors.add(new InvalidField("page", "must be 0 or more"));
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            errors.add(new InvalidField("size", "must be 1 to " + MAX_PAGE_SIZE));
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
        ChatConversationStore.Page stored = store.list(page, size);
        return new ConversationResponses.Page(stored.items().stream()
                .map(c -> new ConversationResponses.Item(c.id(), c.title(), c.turns(), c.updatedAt())).toList(),
                page, size, (int) ((stored.total() + size - 1) / size));
    }

    public ConversationResponses.Detail get(long id) {
        ChatConversationStore.Summary conversation = store.find(id)
                .orElseThrow(() -> new NotFoundException("Conversation", id));
        List<ConversationResponses.Turn> stored = store.turns(id).stream().map(t -> new ConversationResponses.Turn(
                t.seq(), t.question(), t.outboundQuestion(), t.answer(), t.source(),
                t.groundingStatus() == null ? null : new ChatTurnResponse.Grounding(
                        GroundingStatus.valueOf(t.groundingStatus()), t.figuresChecked(), t.retried(), t.ungrounded()),
                refreshed(t.citations()), t.limitHit(),
                new ChatTurnResponse.Usage(t.model(), t.rounds(), t.inputTokens(), t.outputTokens(),
                        t.cacheReadTokens(), t.cacheWriteTokens(), t.callsWithoutUsage(), money(t.estimatedCost())),
                t.createdAt())).toList();
        return new ConversationResponses.Detail(conversation.id(), conversation.title(), conversation.createdAt(),
                conversation.updatedAt(), stored);
    }

    public void delete(long id) {
        if (!store.delete(id)) {
            throw new NotFoundException("Conversation", id);
        }
    }

    // ------------------------------------------------------------------ a turn

    public ChatTurnResponse ask(long conversationId, String question) {
        if (!client.enabled()) {
            throw ChatProblem.aiOff();
        }
        if (!store.exists(conversationId)) {
            throw new NotFoundException("Conversation", conversationId);
        }
        if (!running.add(conversationId)) {
            throw ChatProblem.turnInProgress();
        }
        try {
            if (!acquire()) {
                throw ChatProblem.busy();
            }
            try {
                return answer(conversationId, question);
            } finally {
                overall.release();
            }
        } finally {
            running.remove(conversationId);
        }
    }

    private boolean acquire() {
        try {
            return overall.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private ChatTurnResponse answer(long conversationId, String question) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        if (limits.maxDailyCost().signum() > 0) {
            ZonedDateTime midnight = now.toLocalDate().atStartOfDay(now.getZone());
            if (store.costSince(midnight.toOffsetDateTime()).compareTo(limits.maxDailyCost()) >= 0) {
                throw ChatProblem.budget(midnight.plusDays(1).toOffsetDateTime());
            }
        }
        long started = clock.millis();
        OutboundTurn turn = turns.start();
        QuestionRedactor.OutboundQuestion outbound = redactor.redact(question, turn);
        if (!outbound.sendable()) {
            throw new InvalidRequestException(List.of(new InvalidField("message",
                    "has nothing that can be sent once account numbers and other identifiers are removed")));
        }
        ChatPayloadGuard.Checked<String> sendable = guard.checkText(outbound.text());
        ChatPayloadGuard.Checked<ContextBlockView> block = guard.check(context.build(turn, now.toLocalDate()), turn);
        List<String> names = new ArrayList<>(CategoryNamesQuery.sorted(turn.categories()));
        turn.aliases().entries().forEach(e -> names.add(e.alias()));

        ChatHistory history = memory.window(conversationId);
        TurnLoop.Result result = loop.run(turn, prompt.start(block, history, sendable),
                ChatPrompt.historyMessages(history), outbound.text(), names);
        long millis = Math.max(0, clock.millis() - started);

        CitationResolver.CitedAnswer cited = switch (result.source()) {
            case MODEL -> citations.cite(result.check(), turn.refs());
            case FALLBACK -> citations.citeFallback(result.fallback(), turn.refs());
            case ERROR -> null;
        };
        AnswerCheck check = result.check();
        GroundingStatus status = switch (result.source()) {
            case MODEL -> check.status();
            case FALLBACK -> GroundingStatus.FALLBACK;
            case ERROR -> null;
        };
        int figures = check == null ? 0 : check.figuresChecked();
        int ungrounded = check == null ? 0 : check.ungrounded().size();
        List<Citation> shown = cited == null ? List.of() : cited.citations();

        int seq = store.saveTurn(new ChatConversationStore.NewTurn(conversationId, question, outbound.text(),
                cited == null ? null : cited.text(), result.source(), status == null ? null : status.name(),
                result.retried(), figures, ungrounded, result.limit(), shown, turn.aliases().asMap(), client.modelId(),
                ChatPrompt.VERSION,
                result.rounds(), result.toolCalls(), result.inputTokens(), result.outputTokens(),
                cacheTokens(result, result.cacheReadTokens()), cacheTokens(result, result.cacheWriteTokens()),
                result.callsWithoutUsage(), result.cost(), millis,
                result.error() == null ? null : result.error().getClass().getSimpleName(),
                now.toOffsetDateTime().truncatedTo(ChronoUnit.MICROS),
                result.evidence().results().stream().map(this::toolCall).toList()));

        log.info("chat turn conversation={} turn={} source={} grounding={} retried={} figures={} ungrounded={} "
                        + "rounds={} toolCalls={} limit={} withheldMerchants={} refusedMerchants={} ms={}",
                conversationId, seq, result.source(), status, result.retried(), figures, ungrounded, result.rounds(),
                result.toolCalls(), result.limit(), turn.withheldMerchants(), turn.refusedMerchants(), millis);
        if (result.source() == AnswerSource.ERROR) {
            throw ChatProblem.aiUnavailable(seq);
        }
        memory.foldIfDue(conversationId, turn,
                new ConversationMemory.Spent(started, result.inputForLimits(), result.cost()));

        return new ChatTurnResponse(conversationId, seq, cited.text(), result.source(),
                new ChatTurnResponse.Grounding(status, figures, result.retried(), ungrounded), shown,
                dataNotes(result.evidence()),
                result.evidence().results().stream().map(r -> new ChatTurnResponse.ToolUse(
                        r.outcome().tool() == null ? null : r.outcome().tool().wire(), r.outcome().status(),
                        r.outcome().rows(), r.outcome().millis())).toList(),
                new ChatTurnResponse.Usage(client.modelId(), result.rounds(), result.inputTokens(),
                        result.outputTokens(), cacheTokens(result, result.cacheReadTokens()),
                        cacheTokens(result, result.cacheWriteTokens()), result.callsWithoutUsage(),
                        money(result.cost())),
                result.limit(),
                new ChatTurnResponse.Redactions(outbound.aliasesUsed(), outbound.identifiersRemoved(),
                        turn.withheldMerchants(), turn.refusedMerchants()));
    }

    /** A call as stored; the store keeps a known tool's arguments only, and never an invented name. */
    private ChatConversationStore.NewToolCall toolCall(TurnEvidence.Result result) {
        ToolOutcome outcome = result.outcome();
        return new ChatConversationStore.NewToolCall(outcome.tool() == null ? null : outcome.tool().wire(),
                result.call().arguments(), outcome.status(), outcome.rows(), outcome.shortened(), outcome.millis());
    }

    private static List<DataNote> dataNotes(TurnEvidence evidence) {
        return evidence.results().stream().flatMap(r -> r.outcome().dataNotes().stream()).distinct().toList();
    }

    private List<Citation> refreshed(String stored) {
        List<Citation> read = json.readValue(stored, CITATIONS);
        return read.stream().map(citations::refresh).toList();
    }

    /** A turn's cached-prefix tokens; null when no call reported usage, as its input tokens (step 8c). */
    private static Integer cacheTokens(TurnLoop.Result result, int tokens) {
        return result.inputTokens() == null ? null : tokens;
    }

    private static String money(BigDecimal cost) {
        return cost == null ? null : cost.setScale(6, RoundingMode.HALF_EVEN).toPlainString();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}

package com.finalysis.chat;

import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import com.finalysis.chat.outbound.ChatOutbound;
import com.finalysis.chat.outbound.ChatPayloadGuard;
import com.finalysis.chat.outbound.OutboundProjector;
import com.finalysis.chat.outbound.OutboundTurn;
import com.finalysis.chat.outbound.PayloadRefusedException;
import com.finalysis.chat.outbound.ToolResultWriter;
import com.finalysis.chat.tool.ChatTool;
import com.finalysis.chat.tool.DataNote;
import com.finalysis.chat.tool.DataNotesReader;
import com.finalysis.chat.tool.FieldProblem;
import com.finalysis.chat.tool.ToolArguments;
import com.finalysis.chat.tool.ToolProblem;
import com.finalysis.chat.tool.ToolRead;
import com.finalysis.chat.tool.ToolRegistry;
import com.finalysis.chat.tool.view.DataNotesView;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one tool call (docs/plans/phase-6.md §1, §3, §9), the only way a tool runs and the only way its result reaches
 * a payload:
 * <ol>
 *   <li>the name, against the fixed {@link ToolRegistry}: anything else is an error result;</li>
 *   <li>the arguments, against the tool's closed parameters, then its checks between parameters: a problem is an
 *       error result naming the parameter, never the value;</li>
 *   <li>the read and its data notes, in one {@code READ ONLY}, REPEATABLE READ transaction, so even a tool that forgot
 *       its annotation can't write (Postgres refuses);</li>
 *   <li>the outbound projection and {@link ChatPayloadGuard}, then {@link ToolResultWriter}, which takes only checked
 *       views and caps the size;</li>
 *   <li>one count-only log line: {@code chat tool tool=get_trends status=OK rows=14 ms=38}.</li>
 * </ol>
 * A failure is a tool result, never an exception the model or the log could read: service errors are mapped to fixed
 * messages, anything else is {@link ToolStatus#FAILED} with its exception class logged.
 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry registry;
    private final DataNotesReader notes;
    private final OutboundProjector projector;
    private final ChatOutbound outbound;
    private final ChatPayloadGuard guard;
    private final ToolResultWriter writer;
    private final JsonMapper json;
    private final TransactionTemplate readOnly;

    public ToolExecutor(ToolRegistry registry, DataNotesReader notes, OutboundProjector projector,
                        ChatOutbound outbound, ChatPayloadGuard guard, ToolResultWriter writer, JsonMapper json,
                        PlatformTransactionManager transactions) {
        this.registry = registry;
        this.notes = notes;
        this.projector = projector;
        this.outbound = outbound;
        this.guard = guard;
        this.writer = writer;
        this.json = json;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.readOnly.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    private record Read(ToolRead read, List<DataNote> notes) {
    }

    public ToolOutcome execute(ToolCall call, OutboundTurn turn) {
        long start = System.nanoTime();
        Optional<ChatTool> found = registry.find(call.name());
        if (found.isEmpty()) {
            return error(null, ToolStatus.INVALID_ARGUMENTS, ToolProblem.NOT_A_TOOL, List.of(), turn, start);
        }
        ChatTool tool = found.get();

        ToolArguments.Parsed parsed = ToolArguments.parse(arguments(call.arguments()), tool.params(), turn);
        List<FieldProblem> problems = parsed.ok() ? tool.check(parsed.arguments()) : parsed.problems();
        if (!problems.isEmpty()) {
            return invalid(tool, problems, turn, start);
        }

        Read read;
        try {
            read = Objects.requireNonNull(readOnly.execute(status -> {
                ToolRead result = tool.read(parsed.arguments());
                return new Read(result, notes.notes(result));
            }));
        } catch (InvalidRequestException e) {
            return invalid(tool, e.getErrors().stream().map(ToolExecutor::fromService).flatMap(Optional::stream)
                    .toList(), turn, start);
        } catch (RuntimeException e) {
            return error(tool, ToolStatus.FAILED, ToolProblem.FAILED, List.of(), turn, start, e);
        }

        try {
            int withheld = turn.withheldMerchants();
            int refused = turn.refusedMerchants();
            ChatPayloadGuard.Checked<Record> view = outbound.send(tool.name().wire(),
                    projector.project(read.read(), turn), turn, withheld, refused);
            ChatPayloadGuard.Checked<DataNotesView> noteView = DataNotesReader.carriedByView(read.read()) ? null
                    : guard.check(projector.dataNotes(read.notes(), turn), turn);
            ToolResultWriter.Written written = writer.result(view, noteView);
            return done(new ToolOutcome(tool.name(), ToolStatus.OK, written.json(), view.rowsKept(), millis(start),
                    read.notes(), written.shortened()), null);
        } catch (PayloadRefusedException e) {
            return error(tool, ToolStatus.REFUSED, ToolProblem.REFUSED, List.of(), turn, start);
        } catch (RuntimeException e) {
            return error(tool, ToolStatus.FAILED, ToolProblem.FAILED, List.of(), turn, start, e);
        }
    }

    /** The model's arguments as JSON; blank means none. Malformed JSON parses to null, an error naming no value. */
    private JsonNode arguments(String text) {
        if (text == null || text.isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(text);
        } catch (JacksonException e) {
            return null;
        }
    }

    /**
     * A service's 400 as a tool problem. The tools check every bound first, so this is the range a service resolves
     * from one given end (too long); its own message is never used.
     */
    private static Optional<FieldProblem> fromService(InvalidField field) {
        return switch (field.field()) {
            case "from", "to", "a", "b" -> Optional.of(new FieldProblem(field.field(), ToolProblem.RANGE_TOO_LONG));
            case "limit" -> Optional.of(new FieldProblem("limit", ToolProblem.OUT_OF_BOUNDS));
            default -> Optional.empty();
        };
    }

    private ToolOutcome invalid(ChatTool tool, List<FieldProblem> problems, OutboundTurn turn, long start) {
        return error(tool, ToolStatus.INVALID_ARGUMENTS, ToolProblem.INVALID_ARGUMENTS, problems, turn, start);
    }

    private ToolOutcome error(ChatTool tool, ToolStatus status, ToolProblem message, List<FieldProblem> fields,
                              OutboundTurn turn, long start) {
        return error(tool, status, message, fields, turn, start, null);
    }

    private ToolOutcome error(ChatTool tool, ToolStatus status, ToolProblem message, List<FieldProblem> fields,
                              OutboundTurn turn, long start, RuntimeException cause) {
        String body = writer.error(guard.check(projector.error(status.name(), message, fields), turn));
        return done(new ToolOutcome(tool == null ? null : tool.name(), status, body, 0, millis(start), List.of(),
                false), cause);
    }

    private static ToolOutcome done(ToolOutcome outcome, RuntimeException cause) {
        String name = outcome.tool() == null ? "(unknown)" : outcome.tool().wire();
        if (cause == null) {
            log.info("chat tool tool={} status={} rows={} ms={}", name, outcome.status(), outcome.rows(),
                    outcome.millis());
        } else {
            log.warn("chat tool tool={} status={} rows=0 ms={} error={}", name, outcome.status(), outcome.millis(),
                    cause.getClass().getSimpleName());
        }
        return outcome;
    }

    private static long millis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}

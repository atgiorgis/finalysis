package com.finalysis.categorize.eval;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.finalysis.ai.AiCallLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;

/**
 * Every model call's time as {@link AiCallLog} wrote it (AI-06), read from its log lines while one variant runs
 * (9c-10): the role, the {@code ms} and the outcome of each call, embeddings and failed calls included. The lines hold
 * counts only (never a prompt, merchant or message), and only these three fields are kept. Only the lines of the
 * thread that started recording count: the variant runs on it, and a background search-index refresh, which logs its
 * own embedding calls, must not be counted as the variant's.
 */
final class AiCallTimings implements AutoCloseable {

    private static final Pattern LINE = Pattern.compile("^ai call role=(\\S+) .* ms=(\\d+) outcome=(\\w+)");

    /** One call: its role ({@code categorization}, {@code embedding}), its time and how it ended. */
    record Call(String role, long ms, String outcome) {
    }

    /**
     * One role's calls in a variant, worked out here in Java: the median is the middle call's time, the lower of the
     * two middle ones for an even count.
     */
    record RoleTimes(String role, int calls, long minMs, long medianMs, long maxMs, long totalMs) {
    }

    private final Logger logger = (Logger) LoggerFactory.getLogger(AiCallLog.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final String thread = Thread.currentThread().getName();

    private AiCallTimings() {
        appender.start();
        logger.addAppender(appender);
    }

    /** Starts recording {@link AiCallLog}'s lines; {@link #close()} stops. */
    static AiCallTimings start() {
        return new AiCallTimings();
    }

    /** The calls logged since {@link #start()}, in order. */
    List<Call> calls() {
        List<Call> calls = new ArrayList<>();
        for (ILoggingEvent event : List.copyOf(appender.list)) {
            if (!thread.equals(event.getThreadName())) {
                continue;
            }
            Matcher m = LINE.matcher(event.getFormattedMessage());
            if (m.find()) {
                calls.add(new Call(m.group(1), Long.parseLong(m.group(2)), m.group(3)));
            }
        }
        return List.copyOf(calls);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }

    /** Each role's count, min, median, max and total, by role name. */
    static List<RoleTimes> byRole(List<Call> calls) {
        Map<String, List<Long>> times = new TreeMap<>();
        calls.forEach(call -> times.computeIfAbsent(call.role(), role -> new ArrayList<>()).add(call.ms()));
        List<RoleTimes> roles = new ArrayList<>();
        times.forEach((role, ms) -> {
            List<Long> sorted = ms.stream().sorted().toList();
            roles.add(new RoleTimes(role, sorted.size(), sorted.getFirst(), sorted.get((sorted.size() - 1) / 2),
                    sorted.getLast(), sorted.stream().mapToLong(Long::longValue).sum()));
        });
        return List.copyOf(roles);
    }
}

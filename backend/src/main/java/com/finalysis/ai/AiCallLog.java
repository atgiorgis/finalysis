package com.finalysis.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one log line every model call writes (AI-06, docs/plans/phase-4.md §4.5):
 * <pre>ai call role=categorization model=&lt;id&gt; items=25 inputTokens=1984 outputTokens=471 ms=1830 outcome=OK</pre>
 * Counts only: never a prompt, response, merchant, category or exception message. A failure adds the exception's
 * class name ({@code error=SdkClientException}). Token counts the provider didn't report are written as {@code n/a},
 * never as 0, so a missing count can't pass for a free call. An embedding call has no output tokens, so its line has
 * none: <pre>ai call role=embedding model=&lt;id&gt; items=1 inputTokens=7 ms=210 outcome=OK</pre>
 * A chat round's {@code items} is the number of tool results in its prompt (docs/plans/phase-6.md §8). A chat call
 * also writes its cached-prefix tokens (step 8c), read and written, apart from {@code inputTokens}, which counts only
 * the tokens outside the cached prefix: <pre>... inputTokens=412 cacheReadTokens=5930 cacheWriteTokens=0 ...</pre>
 */
public final class AiCallLog {

    private static final Logger log = LoggerFactory.getLogger(AiCallLog.class);

    /** How a call ended. */
    public enum Outcome {
        /** The model answered and the answer was usable. */
        OK,
        /** The model answered, but not in the requested structure. Tokens were still spent. */
        INVALID_OUTPUT,
        /** The call failed (network, throttling, timeout, credentials...). */
        ERROR,
        /** The model answered, but stopped at the output-token cap; its answer is cut (docs/plans/phase-6.md §8). */
        LIMIT
    }

    private AiCallLog() {
    }

    public static void record(String role, String model, int items, Integer inputTokens, Integer outputTokens,
                              long millis, Outcome outcome, Throwable error) {
        if (error == null) {
            log.info("ai call role={} model={} items={} inputTokens={} outputTokens={} ms={} outcome={}",
                    role, model, items, count(inputTokens), count(outputTokens), millis, outcome);
        } else {
            log.info("ai call role={} model={} items={} inputTokens={} outputTokens={} ms={} outcome={} error={}",
                    role, model, items, count(inputTokens), count(outputTokens), millis, outcome,
                    error.getClass().getSimpleName());
        }
    }

    /** A chat call's line, with its cached-prefix tokens (step 8c). */
    public static void record(String role, String model, int items, Integer inputTokens, Integer outputTokens,
                              int cacheReadTokens, int cacheWriteTokens, long millis, Outcome outcome,
                              Throwable error) {
        if (error == null) {
            log.info("ai call role={} model={} items={} inputTokens={} cacheReadTokens={} cacheWriteTokens={} "
                            + "outputTokens={} ms={} outcome={}", role, model, items, count(inputTokens),
                    cacheReadTokens, cacheWriteTokens, count(outputTokens), millis, outcome);
        } else {
            log.info("ai call role={} model={} items={} inputTokens={} cacheReadTokens={} cacheWriteTokens={} "
                            + "outputTokens={} ms={} outcome={} error={}", role, model, items, count(inputTokens),
                    cacheReadTokens, cacheWriteTokens, count(outputTokens), millis, outcome,
                    error.getClass().getSimpleName());
        }
    }

    /** An embedding call's line: input tokens only, since an embedding has no output tokens. */
    public static void recordEmbedding(String role, String model, int items, Integer inputTokens, long millis,
                                       Outcome outcome, Throwable error) {
        if (error == null) {
            log.info("ai call role={} model={} items={} inputTokens={} ms={} outcome={}",
                    role, model, items, count(inputTokens), millis, outcome);
        } else {
            log.info("ai call role={} model={} items={} inputTokens={} ms={} outcome={} error={}",
                    role, model, items, count(inputTokens), millis, outcome, error.getClass().getSimpleName());
        }
    }

    private static String count(Integer tokens) {
        return tokens == null ? "n/a" : tokens.toString();
    }
}

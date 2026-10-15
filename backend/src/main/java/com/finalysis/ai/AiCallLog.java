package com.finalysis.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one log line every model call writes (AI-06, docs/plans/phase-4.md §4.5):
 * <pre>ai call role=categorization model=&lt;id&gt; items=25 inputTokens=1984 outputTokens=471 ms=1830 outcome=OK</pre>
 * Counts only: never a prompt, response, merchant, category or exception message. A failure adds the exception's
 * class name ({@code error=SdkClientException}). Token counts the provider didn't report are written as {@code -}.
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
        ERROR
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

    private static String count(Integer tokens) {
        return tokens == null ? "-" : tokens.toString();
    }
}

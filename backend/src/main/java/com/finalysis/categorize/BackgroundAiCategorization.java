package com.finalysis.categorize;

import com.finalysis.ai.AiProperties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Runs stage 5 after an import, off the request thread (docs/plans/phase-4.md §1.2), so a slow or failing model call
 * never delays or fails an import. One thread, so runs never overlap, and room for one waiting run: a request that
 * arrives while one is already waiting is dropped, since the waiting run will see its txns too (coalescing).
 *
 * <p>Does nothing when AI is off or {@code finalysis.ai.categorization.run-after-import} is false. A failure is
 * logged with the exception's class name only.
 */
@Component
class BackgroundAiCategorization implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(BackgroundAiCategorization.class);

    private final AiProperties ai;
    private final AiCategorizationProperties properties;
    private final AiCategorizer categorizer;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "ai-categorization");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardPolicy());

    BackgroundAiCategorization(AiProperties ai, AiCategorizationProperties properties, AiCategorizer categorizer) {
        this.ai = ai;
        this.properties = properties;
        this.categorizer = categorizer;
    }

    /** Asks for a run soon; returns at once. */
    void request() {
        if (!ai.enabled() || !properties.runAfterImport()) {
            return;
        }
        executor.execute(() -> {
            try {
                categorizer.run(false);
            } catch (RuntimeException e) {
                log.error("AI categorization in the background failed ({}); the next run catches up",
                        e.getClass().getName());
            }
        });
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}

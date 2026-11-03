package com.finalysis.categorize.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.ai.AiCallLog;
import com.finalysis.categorize.eval.AiCallTimings.Call;
import com.finalysis.categorize.eval.AiCallTimings.RoleTimes;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The per-call times of the categorization evaluation's report (9c-10): read from AiCallLog's own lines. */
class AiCallTimingsTest {

    @Test
    void everyLineOfThisThreadIsReadWhileRecordingAndNoneAfter() throws InterruptedException {
        List<Call> calls;
        try (AiCallTimings timings = AiCallTimings.start()) {
            AiCallLog.record("categorization", "m", 25, 1984, 471, 1830, AiCallLog.Outcome.OK, null);
            AiCallLog.recordEmbedding("embedding", "e", 1, null, 210, AiCallLog.Outcome.OK, null);
            AiCallLog.record("categorization", "m", 25, null, null, 30_000, AiCallLog.Outcome.ERROR,
                    new IllegalStateException("not kept"));
            Thread other = Thread.ofPlatform().start(() -> AiCallLog.recordEmbedding("embedding", "e", 1, 7, 99,
                    AiCallLog.Outcome.OK, null));
            other.join();
            calls = timings.calls();
        }
        AiCallLog.record("categorization", "m", 1, 1, 1, 5, AiCallLog.Outcome.OK, null);

        assertThat(calls).containsExactly(new Call("categorization", 1830, "OK"), new Call("embedding", 210, "OK"),
                new Call("categorization", 30_000, "ERROR"));
    }

    @Test
    void eachRolesMinMedianMaxAndTotal() {
        List<Call> calls = List.of(new Call("categorization", 900, "OK"), new Call("embedding", 40, "OK"),
                new Call("categorization", 300, "OK"), new Call("categorization", 1200, "OK"),
                new Call("categorization", 600, "INVALID_OUTPUT"));

        assertThat(AiCallTimings.byRole(calls)).containsExactly(
                new RoleTimes("categorization", 4, 300, 600, 1200, 3000),
                new RoleTimes("embedding", 1, 40, 40, 40, 40));
    }
}

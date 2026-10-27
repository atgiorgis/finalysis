package com.finalysis.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.vault.VaultFakes.MutableVaultClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The unlock rate limit (docs/plans/phase-8.md §5.2): five free, then 30 s doubling to 15 minutes; success resets. */
class UnlockAttemptsTest {

    private final MutableVaultClock clock = new MutableVaultClock();
    private final UnlockAttempts attempts = new UnlockAttempts(clock);

    @Test
    void fiveFreeThenWaitsThatDoubleUpToFifteenMinutes() {
        for (int i = 0; i < 4; i++) {
            attempts.failed();
            assertThatCode(attempts::check).doesNotThrowAnyException();
        }
        long[] expected = {30, 60, 120, 240, 480, 900, 900};
        for (long seconds : expected) {
            attempts.failed();
            assertThat(retryAfter()).isEqualTo(Duration.ofSeconds(seconds));
            clock.advance(Duration.ofSeconds(seconds - 1));
            assertThatThrownBy(attempts::check).isInstanceOf(VaultException.class);
            clock.advance(Duration.ofSeconds(1));
            assertThatCode(attempts::check).doesNotThrowAnyException();
        }
    }

    @Test
    void aSuccessResets() {
        for (int i = 0; i < 6; i++) {
            attempts.failed();
        }
        clock.advance(Duration.ofMinutes(1));
        attempts.succeeded();
        attempts.failed();
        assertThatCode(attempts::check).doesNotThrowAnyException();
    }

    @Test
    void theProblemSaysHowLongToWait() {
        for (int i = 0; i < 5; i++) {
            attempts.failed();
        }
        assertThatThrownBy(attempts::check).hasMessageContaining("Wait 30 seconds");
        for (int i = 0; i < 5; i++) {
            attempts.failed();
        }
        assertThatThrownBy(attempts::check).hasMessageContaining("Wait 15 minutes");
    }

    private Duration retryAfter() {
        try {
            attempts.check();
            return Duration.ZERO;
        } catch (VaultException e) {
            return e.retryAfter();
        }
    }
}

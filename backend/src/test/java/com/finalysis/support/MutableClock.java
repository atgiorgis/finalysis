package com.finalysis.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * A clock tests set and move (the {@code Clock} bean in {@link AiIntegrationTest} contexts). It starts at
 * {@link #SAMPLE_TODAY}, noon, in the system zone: the sample data's "today" (docs/plans/phase-6.md §13), which step 8's
 * evaluation pins too.
 */
public class MutableClock extends Clock {

    public static final LocalDateTime SAMPLE_TODAY = LocalDateTime.of(2026, 10, 15, 12, 0);

    private final ZoneId zone;
    private volatile Instant now;

    public MutableClock() {
        this(ZoneId.systemDefault());
    }

    private MutableClock(ZoneId zone) {
        this.zone = zone;
        reset();
    }

    /** Back to {@link #SAMPLE_TODAY}. */
    public MutableClock reset() {
        now = SAMPLE_TODAY.atZone(zone).toInstant();
        return this;
    }

    public void set(LocalDateTime local) {
        now = local.atZone(zone).toInstant();
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock clock = new MutableClock(zone);
        clock.now = now;
        return clock;
    }

    @Override
    public Instant instant() {
        return now;
    }
}

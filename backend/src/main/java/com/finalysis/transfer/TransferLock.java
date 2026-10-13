package com.finalysis.transfer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one lock that detection and every transfer write take (docs/plans/phase-3c.md §2.5), so two
 * runs can't interleave. A transaction-level advisory lock: released at commit or rollback, never
 * held across requests. Statement delete takes its account lock first, then this one; detection
 * takes no account locks, so the order is fixed and can't deadlock.
 */
@Component
public class TransferLock {

    /** "transfer" in ASCII. The app takes no other advisory lock. */
    static final long KEY = 0x7472616E73666572L;

    private final JdbcClient jdbc;

    public TransferLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Blocks until this transaction holds the lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        // pg_advisory_xact_lock returns void; the row mapper ignores the column.
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", KEY).query((rs, row) -> true).single();
    }
}

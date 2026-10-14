package com.finalysis.categorize;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lock every categorization write pass takes (docs/plans/phase-4.md §1.3): the after-import
 * listener, the startup backfill and, from step 2, the on-demand run never interleave. A
 * transaction-level advisory lock like {@code TransferLock}, with its own key; the two are never
 * nested in either order, since categorization never takes the transfer lock and transfer code never
 * takes this one.
 */
@Component
public class CategorizationLock {

    /** "category" in ASCII. */
    static final long KEY = 0x63617465676F7279L;

    private final JdbcClient jdbc;

    public CategorizationLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Blocks until this transaction holds the lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", KEY).query((rs, row) -> true).single();
    }
}

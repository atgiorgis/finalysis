package com.finalysis.vault;

/**
 * Published, synchronously, when a lock starts and before the database closes (docs/plans/phase-8.md §4.6): background
 * work stops and drops what it was waiting to do. Requests are no longer admitted by then.
 */
public record VaultLocking() {
}

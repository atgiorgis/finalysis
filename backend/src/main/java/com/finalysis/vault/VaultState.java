package com.finalysis.vault;

/** The vault's state on {@code GET /api/status} (docs/plans/phase-8.md §5.2). Every state but UNLOCKED answers 423. */
public enum VaultState {
    /** No key file and no image: setup is allowed. */
    NOT_SET_UP,
    /** The startup check failed (§4.4): no PostgreSQL 17, the wrong pgvector, a conflict or the port in use. */
    NOT_READY,
    /** Set up and locked: unlock or recover. */
    LOCKED,
    /** Unlock, recovery or setup in progress. */
    UNLOCKING,
    /** The data is open. */
    UNLOCKED,
    /** A lock is in progress; requests are no longer admitted. */
    LOCKING,
    /** A part of the lock failed; the status names it, and every gated request is still refused. */
    LOCK_FAILED,
    /** A key file without an image or the reverse, or the image attached elsewhere: never repaired by the app. */
    BROKEN
}

package com.finalysis.vault;

/**
 * The vault on {@code GET /api/status} (docs/plans/phase-8.md §5.2). Never a path, a key or a password.
 *
 * @param state           see {@link VaultState}
 * @param storage         {@code VAULT}, or {@code PLAIN} with the vault off (development and tests)
 * @param setUp           a vault exists (key file and image): the screens offer unlock rather than setup
 * @param reason          why NOT_READY, LOCK_FAILED or BROKEN: a reason code, or the part that failed
 * @param message         one plain sentence for that reason, when there is one
 * @param idleLockMinutes the idle lock in force
 * @param lanAccess       phone access (step 5); false until then
 * @param lastLockForced  the last lock had to force the unmount (§4.6)
 * @param collation       set when the database's ICU collation version changed (§4.4): what to run
 */
public record VaultStatus(VaultState state, String storage, boolean setUp, String reason, String message,
                          int idleLockMinutes, boolean lanAccess, boolean lastLockForced, Collation collation) {

    /**
     * @param recorded the version the database recorded
     * @param actual   the version Postgres.app's ICU reports now
     * @param action   the step to take, in plain words
     */
    public record Collation(String recorded, String actual, String action) {
    }
}

// The capture's refusals (docs/plans/phase-8.md §8.2): fixtures are committed, so they come only from the plain dev
// database holding the sample data, with AI off. A backend with the vault on may hold real statements: never.

/** Throws unless the status is a plain-database backend with AI off; returns the status otherwise. */
export function refuseUnlessPlain(status) {
  const storage = status?.vault?.storage
  if (storage !== 'PLAIN') {
    throw new Error(
      `Refusing to capture fixtures: the backend's storage is ${storage ?? 'unknown'}, not PLAIN. ` +
        'Capture only from the plain dev database with the sample data (FINALYSIS_VAULT_ENABLED=false); ' +
        'a vault may hold real statements, and fixtures are committed.',
    )
  }
  if (status?.ai?.path !== 'OFF') {
    throw new Error(`AI must be off while capturing fixtures (path ${status?.ai?.path})`)
  }
  return status
}

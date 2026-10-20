-- Closed accounts (Phase 5 step 3, docs/plans/phase-5.md §1.6). Coverage expects each account from its first
-- statement to the last day any account covers; a closed account is expected only up to closed_on, so the months
-- after it closed are not PARTIAL. Null = open. Set and cleared by the user (PUT/DELETE /api/accounts/{id}/closure),
-- never inferred: a statement that hasn't been imported yet looks the same as a closed account.

ALTER TABLE account
    ADD COLUMN closed_on DATE;

COMMENT ON COLUMN account.closed_on IS
    'The last day the account was open, or null while it is open. Analytics expects no statement after it.';

-- One account per institution, type and last four digits (DAT-01).
--
-- Institution is compared case-insensitively and without surrounding spaces, so "Northfield Bank"
-- and " northfield bank" are the same institution. The name is not part of the key: two accounts
-- may share a name, and a rename never conflicts. last_four is NOT NULL (V1), so the index is not
-- partial. The API inserts with ON CONFLICT on this index and answers 409 account-exists.
--
-- Existing duplicates are never merged or deleted here: statements and transactions may hang off
-- either row. The migration stops and names the account ids instead; a dev database is reset with
-- scripts/reset-dev-db.sh. Only ids are reported, never institutions or digits.

DO $$
DECLARE
    groups TEXT;
BEGIN
    SELECT string_agg(ids, '; ' ORDER BY ids)
    INTO groups
    FROM (
        SELECT string_agg(id::TEXT, ', ' ORDER BY id) AS ids
        FROM account
        GROUP BY lower(btrim(institution)), type, last_four
        HAVING count(*) > 1
    ) duplicates;

    IF groups IS NOT NULL THEN
        RAISE EXCEPTION 'V12: duplicate accounts (same institution, type and last four), account ids: %', groups
            USING HINT = 'Merge or delete the duplicates by hand, or reset a dev database with scripts/reset-dev-db.sh, then restart.';
    END IF;
END
$$;

CREATE UNIQUE INDEX uq_account_identity ON account (lower(btrim(institution)), type, last_four);

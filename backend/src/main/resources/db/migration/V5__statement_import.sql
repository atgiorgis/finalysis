-- Statement import (ING-03, ING-04, Phase 3a step 4).

-- The institution's own transaction id (OFX FITID) and the amount exactly as printed (DAT-07).
-- Deduplication stays on the fingerprint; external_id only flags possible duplicates whose
-- details changed between downloads, so it is indexed per account but not unique.
ALTER TABLE txn
    ADD COLUMN external_id    TEXT,
    ADD COLUMN printed_amount TEXT,
    ADD CONSTRAINT ck_txn_external_id_length    CHECK (char_length(external_id) BETWEEN 1 AND 255),
    ADD CONSTRAINT ck_txn_printed_amount_length CHECK (char_length(printed_amount) BETWEEN 1 AND 64);

CREATE INDEX ix_txn_account_external_id ON txn (account_id, external_id) WHERE external_id IS NOT NULL;

-- Which StatementParser read the file. Rows imported before this migration were never read by
-- a parser, so they are backfilled as 'unknown'; new rows must name their parser.
ALTER TABLE statement ADD COLUMN parser TEXT NOT NULL DEFAULT 'unknown';
ALTER TABLE statement ALTER COLUMN parser DROP DEFAULT;
ALTER TABLE statement ADD CONSTRAINT ck_statement_parser CHECK (parser ~ '^[a-z0-9][a-z0-9-]{0,31}$');

-- Why an import needs review (ING-07): the warnings from the import summary, kept for the review
-- queue. Line numbers, counts and statement ids only, never file contents (SEC-10).
ALTER TABLE statement
    ADD COLUMN import_warnings JSONB NOT NULL DEFAULT '[]',
    ADD CONSTRAINT ck_statement_import_warnings_array CHECK (jsonb_typeof(import_warnings) = 'array');

-- Re-importing a period is allowed (ING-04): rows already imported are skipped by fingerprint,
-- and the import summary warns about the overlap. An identical file is still blocked by
-- uq_statement_file_hash.
ALTER TABLE statement DROP CONSTRAINT uq_statement_account_period;
CREATE INDEX ix_statement_account_period ON statement (account_id, period_start, period_end);

-- No (account_id, fingerprint) constraint is added: uq_txn_fingerprint (V1) is already unique
-- across all accounts, which is stricter, and the fingerprint itself hashes the account id.

-- Statement reconciliation (ING-06, ING-10, Phase 3a step 8).
--
-- balance_check is the outcome of the balance checks, separate from status: a statement can be
-- NEEDS_REVIEW for other reasons (an overlap, mixed formats) while its balances are unverified,
-- and a later import of the previous statement must still find it to re-run the continuity check.
-- parsed_count and parsed_amount_total describe the rows parsed from the file, duplicates
-- included, so that re-check uses the file's rows rather than only the rows this statement
-- inserted. parsed_amount_total is a sum of many amounts, so it gets two more digits than a single
-- amount. Statements imported before V7 have no parsed totals and stay unverified.

ALTER TABLE statement
    ADD COLUMN balance_check       TEXT           NOT NULL DEFAULT 'UNVERIFIED',
    ADD COLUMN parsed_count        INTEGER,
    ADD COLUMN parsed_amount_total NUMERIC(14, 2),
    ADD CONSTRAINT ck_statement_balance_check CHECK (balance_check IN ('PASSED', 'FAILED', 'UNVERIFIED')),
    ADD CONSTRAINT ck_statement_parsed_count CHECK (parsed_count >= 0),
    ADD CONSTRAINT ck_statement_parsed_totals CHECK ((parsed_count IS NULL) = (parsed_amount_total IS NULL));

COMMENT ON COLUMN statement.balance_check IS
    'PASSED: at least one balance check ran and all passed; FAILED: one failed; UNVERIFIED: none could run.';

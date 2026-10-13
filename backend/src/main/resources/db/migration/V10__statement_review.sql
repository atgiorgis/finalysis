-- Statement review (ING-07, Phase 3c step 5).
--
-- reviewed_at records that the user accepted a NEEDS_REVIEW statement: the status and the warnings
-- are kept, because they record what the import found. A late arrival whose re-check adds a warning
-- clears reviewed_at again, so the statement returns to the review queue.

ALTER TABLE statement
    ADD COLUMN reviewed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_statement_reviewed CHECK (reviewed_at IS NULL OR status = 'NEEDS_REVIEW');

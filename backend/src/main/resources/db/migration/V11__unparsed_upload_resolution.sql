-- Unparsed upload resolution (ING-13, Phase 3c step 6).
--
-- V8 told "resolved" from "dismissed" by resolved_statement_id alone, so deleting the resolving
-- statement (ON DELETE SET NULL) made a resolved upload read as dismissed. resolution records the
-- outcome itself: NULL while open, then RESOLVED (uploaded again and imported) or DISMISSED (the user
-- dismissed it). A RESOLVED upload keeps its resolution after its statement is deleted;
-- resolved_statement_id then becomes null as before.

ALTER TABLE unparsed_upload ADD COLUMN resolution TEXT;

-- Backfill from V8's rule. An upload whose resolving statement was already deleted can't be told from
-- a dismissed one any more; it becomes DISMISSED, as it already read.
UPDATE unparsed_upload
SET resolution = CASE WHEN resolved_statement_id IS NOT NULL THEN 'RESOLVED' ELSE 'DISMISSED' END
WHERE resolved_at IS NOT NULL;

ALTER TABLE unparsed_upload
    ADD CONSTRAINT ck_unparsed_upload_resolution_kind CHECK (resolution IN ('RESOLVED', 'DISMISSED')),
    -- Open exactly when resolved_at is null.
    ADD CONSTRAINT ck_unparsed_upload_resolution_resolved_at CHECK ((resolution IS NULL) = (resolved_at IS NULL)),
    -- Only a RESOLVED upload has a statement (and may lose it when the statement is deleted).
    ADD CONSTRAINT ck_unparsed_upload_resolution_statement
        CHECK (resolved_statement_id IS NULL OR resolution = 'RESOLVED');

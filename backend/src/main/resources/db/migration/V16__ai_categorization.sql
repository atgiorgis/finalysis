-- AI categorization (CAT-02, Phase 4 step 7; docs/plans/phase-4.md §4, step 7 notes).
--
-- An AI category records where it came from: the model id, the model's confidence (0 to 1) and the version
-- of the prompt that asked. A txn the AI looked at but could not categorize (its answer was below the
-- confidence threshold, or invalid twice) stays uncategorized and records why, for the review queue.
-- Every column here is written only by the categorization code, with SQL, under the categorization lock.

ALTER TABLE txn
    ADD COLUMN category_model          TEXT,
    ADD COLUMN category_confidence     NUMERIC(4,3),
    ADD COLUMN category_prompt_version TEXT,
    ADD COLUMN ai_review_reason        TEXT,
    ADD COLUMN ai_review_at            TIMESTAMPTZ,
    ADD CONSTRAINT ck_txn_category_ai_provenance CHECK (category_source = 'AI'
        OR (category_model IS NULL AND category_confidence IS NULL AND category_prompt_version IS NULL)),
    ADD CONSTRAINT ck_txn_category_confidence CHECK (category_confidence BETWEEN 0 AND 1),
    ADD CONSTRAINT ck_txn_ai_review_reason CHECK (ai_review_reason IN ('LOW_CONFIDENCE', 'INVALID_ANSWER')),
    ADD CONSTRAINT ck_txn_ai_review_has_time CHECK ((ai_review_reason IS NULL) = (ai_review_at IS NULL)),
    -- Waiting for review means uncategorized: any category write clears the reason.
    ADD CONSTRAINT ck_txn_ai_review_uncategorized CHECK (ai_review_reason IS NULL OR category_id IS NULL);

-- The review queue lists these; few rows ever have a reason.
CREATE INDEX ix_txn_ai_review ON txn (ai_review_at) WHERE ai_review_reason IS NOT NULL;

COMMENT ON COLUMN txn.category_model IS
    'The model id that gave an AI category (or the txn it was copied from by merchant memory). Null otherwise.';
COMMENT ON COLUMN txn.category_confidence IS
    'The model''s own confidence in an AI category, 0 to 1, as it answered (three decimals). Null otherwise.';
COMMENT ON COLUMN txn.category_prompt_version IS
    'The categorization prompt version that asked for an AI category. Null otherwise.';
COMMENT ON COLUMN txn.ai_review_reason IS
    'Why the AI left this txn uncategorized: LOW_CONFIDENCE (below the threshold) or INVALID_ANSWER (two '
    'invalid answers). Listed in the review queue; AI runs skip it unless forced. Cleared by any category write.';
COMMENT ON COLUMN txn.ai_review_at IS
    'When the AI left this txn for review.';

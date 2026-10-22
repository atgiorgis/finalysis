-- Prompt caching of chat's fixed prefix (Phase 6 step 8c, docs/plans/phase-6.md step 8c notes): a turn and a ledger row
-- record the cached-prefix tokens read and written apart from input_tokens, which keeps counting only what the provider
-- reports as input outside the cached prefix. Each kind has its own price, so the estimated cost already includes them;
-- these columns say what it was made of. Null when no call reported usage, like input_tokens; 0 when nothing was
-- cached. Rows written before V24 stay null: they were never cached.
ALTER TABLE chat_turn
    ADD COLUMN cache_read_tokens  INT,
    ADD COLUMN cache_write_tokens INT,
    ADD CONSTRAINT ck_chat_turn_cache_tokens CHECK (cache_read_tokens >= 0 AND cache_write_tokens >= 0);

ALTER TABLE chat_usage
    ADD COLUMN cache_read_tokens  INT,
    ADD COLUMN cache_write_tokens INT,
    ADD CONSTRAINT ck_chat_usage_cache_tokens CHECK (cache_read_tokens >= 0 AND cache_write_tokens >= 0);

COMMENT ON COLUMN chat_turn.cache_read_tokens IS 'Cached-prefix tokens read over the turn''s calls (step 8c).';
COMMENT ON COLUMN chat_turn.cache_write_tokens IS 'Cached-prefix tokens written over the turn''s calls (step 8c).';
COMMENT ON COLUMN chat_usage.cache_read_tokens IS 'Cached-prefix tokens read (step 8c).';
COMMENT ON COLUMN chat_usage.cache_write_tokens IS 'Cached-prefix tokens written (step 8c).';

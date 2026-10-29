-- Where each chat turn and ledger row ran, and what its stored cost means there (Phase 9 step 9b-1,
-- docs/plans/phase-9.md §4.2, §16 decision 5).
--
--   CLOUD        the token cost estimated from the price table, as before (null when the model had no price);
--   LOCAL        0: nothing is paid per token on this computer;
--   SELF_HOSTED  NULL: the cost is the server's running time, not tokens (shown from step 9c).
-- The daily cap and "this month" sum Cloud rows only, in SQL (ChatConversationStore.costSince). Every row written
-- before this migration ran on Cloud, the only path there was, so the column defaults to CLOUD; the app always writes
-- the path of the turn or call it records.

ALTER TABLE chat_turn ADD COLUMN path TEXT NOT NULL DEFAULT 'CLOUD';
ALTER TABLE chat_turn
    ADD CONSTRAINT ck_chat_turn_path CHECK (path IN ('CLOUD', 'SELF_HOSTED', 'LOCAL')),
    ADD CONSTRAINT ck_chat_turn_path_cost CHECK (
        (path <> 'LOCAL' OR estimated_cost = 0) AND (path <> 'SELF_HOSTED' OR estimated_cost IS NULL));

ALTER TABLE chat_usage ADD COLUMN path TEXT NOT NULL DEFAULT 'CLOUD';
ALTER TABLE chat_usage
    ADD CONSTRAINT ck_chat_usage_path CHECK (path IN ('CLOUD', 'SELF_HOSTED', 'LOCAL')),
    ADD CONSTRAINT ck_chat_usage_path_cost CHECK (
        (path <> 'LOCAL' OR estimated_cost = 0) AND (path <> 'SELF_HOSTED' OR estimated_cost IS NULL));

COMMENT ON COLUMN chat_turn.path IS 'Where the turn ran: CLOUD, SELF_HOSTED or LOCAL; decides what estimated_cost means.';
COMMENT ON COLUMN chat_usage.path IS 'Where the call ran; the daily cap and the month''s spend sum CLOUD rows only.';

-- Chat usage ledger (Phase 6 step 7, docs/plans/phase-6.md §8, step 7 notes). One row per chat turn, conversation
-- summary and monthly summary, with its tokens and estimated cost: the daily cost cap sums this table. Counts only:
-- no conversation id, question, answer, month or figure, so deleting a conversation removes all of its content while
-- the day's spend stays counted (a delete can't reset the cap). Written only by ChatConversationStore; rows older than
-- the conversation retention are purged with idle conversations.

CREATE TABLE chat_usage (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    kind                TEXT          NOT NULL,   -- TURN, CONVERSATION_SUMMARY, MONTHLY_SUMMARY
    model               TEXT          NOT NULL,
    calls               INT           NOT NULL,
    input_tokens        INT,                      -- reported only; null when no call reported usage
    output_tokens       INT,
    calls_without_usage INT           NOT NULL DEFAULT 0,
    estimated_cost      NUMERIC(10,6),            -- from the price table; null when unpriced
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_chat_usage PRIMARY KEY (id),
    CONSTRAINT ck_chat_usage_kind CHECK (kind IN ('TURN', 'CONVERSATION_SUMMARY', 'MONTHLY_SUMMARY')),
    CONSTRAINT ck_chat_usage_counts CHECK (calls >= 0 AND calls_without_usage >= 0 AND input_tokens >= 0
        AND output_tokens >= 0),
    CONSTRAINT ck_chat_usage_cost CHECK (estimated_cost >= 0)
);

CREATE INDEX ix_chat_usage_created ON chat_usage (created_at);

-- Turns stored before this migration keep counting toward today's cap.
INSERT INTO chat_usage (kind, model, calls, input_tokens, output_tokens, calls_without_usage, estimated_cost,
                        created_at)
SELECT 'TURN', model, rounds, input_tokens, output_tokens, calls_without_usage, estimated_cost, created_at
FROM chat_turn;

COMMENT ON TABLE chat_usage IS
    'Tokens and estimated cost of every chat, summary and monthly-summary call, for the daily cap. Counts only.';

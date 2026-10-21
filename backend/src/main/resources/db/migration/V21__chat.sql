-- Chat conversations (Phase 6 step 5, docs/plans/phase-6.md §7.3). Written only by ChatConversationStore, outside the
-- tools' read-only transactions; nothing in chat.tool may reach it (ChatReadOnlyTest). Questions are kept as typed and
-- answers as shown: the user's own data, local like the statements (unencrypted until SEC-05, Phase 8). Tool results
-- are not stored: they are recomputed on demand. A conversation is deleted by the user (cascading to its turns); the
-- idle-retention purge comes with conversation memory (step 7).

CREATE TABLE chat_conversation (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    title           TEXT,                              -- first question, cut to 80 characters; local only
    summary         TEXT,                              -- older turns, figures stripped (step 7)
    summary_through INT         NOT NULL DEFAULT 0,    -- last turn folded into the summary
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_chat_conversation PRIMARY KEY (id),
    CONSTRAINT ck_chat_conversation_title CHECK (char_length(title) BETWEEN 1 AND 80),
    CONSTRAINT ck_chat_conversation_summary CHECK (char_length(summary) <= 4000),
    CONSTRAINT ck_chat_conversation_summary_through CHECK (summary_through >= 0)
);

CREATE INDEX ix_chat_conversation_updated ON chat_conversation (updated_at DESC, id DESC);

CREATE TABLE chat_turn (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    conversation_id     BIGINT        NOT NULL,
    seq                 INT           NOT NULL,
    question            TEXT          NOT NULL,   -- as typed; local only
    outbound_question   TEXT          NOT NULL,   -- what was sent
    answer              TEXT,                     -- as shown; null when the turn failed
    answer_source       TEXT          NOT NULL,   -- MODEL, FALLBACK, ERROR
    grounding_status    TEXT,                     -- GROUNDED, NO_FIGURES, FALLBACK; null when the turn failed
    retried             BOOLEAN       NOT NULL DEFAULT false,
    figures_checked     INT           NOT NULL DEFAULT 0,
    ungrounded          INT           NOT NULL DEFAULT 0,   -- count only; the discarded text is never kept
    limit_hit           TEXT,                     -- ROUNDS, TOOL_CALLS, TOKENS, TIME, COST
    citations           JSONB         NOT NULL DEFAULT '[]',
    alias_map           JSONB         NOT NULL,   -- alias -> account id, as of the turn
    model               TEXT          NOT NULL,
    prompt_version      TEXT          NOT NULL,
    rounds              INT           NOT NULL,
    tool_calls          INT           NOT NULL,
    input_tokens        INT,                      -- reported only; null when no call reported usage
    output_tokens       INT,
    calls_without_usage INT           NOT NULL DEFAULT 0,
    estimated_cost      NUMERIC(10,6),            -- from the price table; null when unpriced
    millis              INT           NOT NULL,
    error               TEXT,                     -- exception class only
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_chat_turn PRIMARY KEY (id),
    CONSTRAINT fk_chat_turn_conversation FOREIGN KEY (conversation_id)
        REFERENCES chat_conversation (id) ON DELETE CASCADE,
    CONSTRAINT uq_chat_turn_seq UNIQUE (conversation_id, seq),
    CONSTRAINT ck_chat_turn_seq CHECK (seq >= 1),
    CONSTRAINT ck_chat_turn_question CHECK (char_length(question) BETWEEN 1 AND 1000),
    CONSTRAINT ck_chat_turn_outbound_question CHECK (char_length(outbound_question) BETWEEN 1 AND 1000),
    CONSTRAINT ck_chat_turn_answer_source CHECK (answer_source IN ('MODEL', 'FALLBACK', 'ERROR')),
    CONSTRAINT ck_chat_turn_grounding CHECK (grounding_status IN ('GROUNDED', 'NO_FIGURES', 'FALLBACK')),
    CONSTRAINT ck_chat_turn_answer CHECK ((answer IS NULL) = (answer_source = 'ERROR')),
    CONSTRAINT ck_chat_turn_answer_grounding CHECK ((grounding_status IS NULL) = (answer_source = 'ERROR')),
    CONSTRAINT ck_chat_turn_fallback CHECK ((answer_source = 'FALLBACK') = (grounding_status = 'FALLBACK')
        OR answer_source = 'ERROR'),
    CONSTRAINT ck_chat_turn_limit CHECK (limit_hit IN ('ROUNDS', 'TOOL_CALLS', 'TOKENS', 'TIME', 'COST')),
    CONSTRAINT ck_chat_turn_citations_array CHECK (jsonb_typeof(citations) = 'array'),
    CONSTRAINT ck_chat_turn_alias_map_object CHECK (jsonb_typeof(alias_map) = 'object'),
    CONSTRAINT ck_chat_turn_counts CHECK (rounds >= 0 AND tool_calls >= 0 AND figures_checked >= 0
        AND ungrounded >= 0 AND calls_without_usage >= 0 AND millis >= 0),
    CONSTRAINT ck_chat_turn_tokens CHECK (input_tokens >= 0 AND output_tokens >= 0),
    CONSTRAINT ck_chat_turn_cost CHECK (estimated_cost >= 0),
    CONSTRAINT ck_chat_turn_error CHECK ((error IS NOT NULL) = (answer_source = 'ERROR'))
);

-- The daily cost cap sums the day's turns (docs/plans/phase-6.md §8).
CREATE INDEX ix_chat_turn_created ON chat_turn (created_at);

CREATE TABLE chat_tool_call (
    id        BIGINT GENERATED ALWAYS AS IDENTITY,
    turn_id   BIGINT      NOT NULL,
    seq       INT         NOT NULL,
    tool      TEXT        NOT NULL,   -- a registry name, or '(unknown)': a name the model invented is never stored
    arguments JSONB       NOT NULL,   -- as the model sent them, when a JSON object (only outbound names); else {}
    status    TEXT        NOT NULL,
    rows      INT         NOT NULL DEFAULT 0,
    truncated BOOLEAN     NOT NULL DEFAULT false,
    millis    INT         NOT NULL,
    CONSTRAINT pk_chat_tool_call PRIMARY KEY (id),
    CONSTRAINT fk_chat_tool_call_turn FOREIGN KEY (turn_id) REFERENCES chat_turn (id) ON DELETE CASCADE,
    CONSTRAINT uq_chat_tool_call_seq UNIQUE (turn_id, seq),
    CONSTRAINT ck_chat_tool_call_status CHECK (status IN ('OK', 'INVALID_ARGUMENTS', 'FAILED', 'REFUSED',
        'SKIPPED_LIMIT')),
    CONSTRAINT ck_chat_tool_call_arguments_object CHECK (jsonb_typeof(arguments) = 'object'),
    CONSTRAINT ck_chat_tool_call_counts CHECK (seq >= 1 AND rows >= 0 AND millis >= 0)
);

COMMENT ON TABLE chat_conversation IS 'Chat conversations (CHT-06). Local only; deleted by the user.';
COMMENT ON TABLE chat_turn IS
    'One question and its answer as shown, with grounding, citations and usage. Tool results are never stored.';
COMMENT ON TABLE chat_tool_call IS 'The tool calls of a turn: name, arguments and outcome counts, for the evaluation.';

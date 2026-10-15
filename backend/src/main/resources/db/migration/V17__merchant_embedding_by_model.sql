-- Merchant embeddings for similar-merchant examples (CAT-03, DAT-06; Phase 4 step 8, docs/plans/phase-4.md §6 and
-- the step 8 notes).
--
-- V1's table kept one vector per merchant. A vector is only comparable with vectors from the same model, so this
-- one keeps one per merchant and model: switching models (Titan v2 now, a local Qwen3-Embedding later, both 1024
-- dimensions but not comparable) re-embeds into new rows instead of overwriting, and similarity search filters on
-- the model. Nothing wrote to V1's table before this step (it had only repository tests), so it is replaced, not
-- migrated.
--
-- What was embedded is the redacted, guard-checked merchant text (the text a chat call would send), never the raw
-- description. Its SHA-256 is kept, so a merchant is embedded again only when that text or the model changes.

DROP TABLE merchant_embedding;

CREATE TABLE merchant_embedding (
    merchant     TEXT         NOT NULL,
    model        TEXT         NOT NULL,
    dimensions   INTEGER      NOT NULL,
    input_sha256 TEXT         NOT NULL,
    embedding    vector(1024) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_merchant_embedding PRIMARY KEY (merchant, model),
    CONSTRAINT ck_merchant_embedding_merchant CHECK (merchant <> ''),
    CONSTRAINT ck_merchant_embedding_model CHECK (model <> ''),
    CONSTRAINT ck_merchant_embedding_dimensions CHECK (dimensions = 1024 AND vector_dims(embedding) = dimensions),
    CONSTRAINT ck_merchant_embedding_input_sha256 CHECK (input_sha256 ~ '^[0-9a-f]{64}$')
);

-- Nearest neighbours by cosine distance (<=>). The query filters on model after the index scan.
CREATE INDEX ix_merchant_embedding_hnsw ON merchant_embedding USING hnsw (embedding vector_cosine_ops);

COMMENT ON TABLE merchant_embedding IS
    'One embedding per cleaned merchant (txn.merchant) and embedding model. Only vectors of the same model are compared.';
COMMENT ON COLUMN merchant_embedding.model IS
    'The embedding model id (finalysis.ai.roles.embedding) that produced the vector.';
COMMENT ON COLUMN merchant_embedding.input_sha256 IS
    'SHA-256 (hex) of the redacted outbound text that was embedded; a different text means the row is stale.';

-- The chat search index (CHT-02; Phase 6 step 8b, docs/plans/phase-6.md step 8b notes), apart from categorization's
-- merchant vectors.
--
-- The first dev run found that Titan places a brand name far from what it is ("gas" vs SHELL, "food delivery" vs
-- DOORDASH): the bare merchant text carries too little meaning. So search embeds richer documents, one per subject and
-- embedding model:
--   MERCHANT  subject = the stored merchant (txn.merchant); document = its redacted, guard-checked outbound text plus
--             its category name, "SHELL OIL (Transportation)";
--   CATEGORY  subject = a category name; document = the name, so a search can match a category by meaning and offer
--             its merchants.
-- Categorization keeps merchant_embedding as it is: its similar-merchant examples compare a merchant's text with other
-- merchants' texts, and a document carrying a category would leak the answer into the comparison.
--
-- What was embedded is never stored, only its SHA-256, so a subject is embedded again when its document (a renamed
-- merchant text, a new majority category) or the model changes. Written only by categorize.MerchantIndexRefresh; chat
-- reads it.

CREATE TABLE search_embedding (
    kind         TEXT         NOT NULL,
    subject      TEXT         NOT NULL,
    model        TEXT         NOT NULL,
    dimensions   INTEGER      NOT NULL,
    input_sha256 TEXT         NOT NULL,
    embedding    vector(1024) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_search_embedding PRIMARY KEY (kind, subject, model),
    CONSTRAINT ck_search_embedding_kind CHECK (kind IN ('MERCHANT', 'CATEGORY')),
    CONSTRAINT ck_search_embedding_subject CHECK (subject <> ''),
    CONSTRAINT ck_search_embedding_model CHECK (model <> ''),
    CONSTRAINT ck_search_embedding_dimensions CHECK (dimensions = 1024 AND vector_dims(embedding) = dimensions),
    CONSTRAINT ck_search_embedding_input_sha256 CHECK (input_sha256 ~ '^[0-9a-f]{64}$')
);

-- Nearest neighbours by cosine distance (<=>). Queries filter on kind and model after the index scan.
CREATE INDEX ix_search_embedding_hnsw ON search_embedding USING hnsw (embedding vector_cosine_ops);

COMMENT ON TABLE search_embedding IS
    'The chat search index: one embedding per merchant or category and embedding model. Only vectors of the same model are compared.';
COMMENT ON COLUMN search_embedding.input_sha256 IS
    'SHA-256 (hex) of the document that was embedded (outbound merchant text and category name, or the category name).';

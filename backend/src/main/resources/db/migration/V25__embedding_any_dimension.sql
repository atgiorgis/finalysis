-- Embeddings of any length, one model at a time (Phase 9 step 9b-1, docs/plans/phase-9.md §4.1, §16).
--
-- Cloud embeds with Titan V2 (1024 dimensions); the Self-hosted and Local paths with nomic-embed-text through Ollama
-- (768). Both tables were vector(1024) with an HNSW index. Now:
--   - the embedding column is vector with no fixed length, and each row's length is checked against its dimensions
--     column, which the app sets from the vector it stores;
--   - the HNSW indexes are dropped: an HNSW index needs one length, and search is an exact scan of one model's rows.
--     A household has hundreds to a few thousand merchants; an exact cosine scan of those takes milliseconds and is
--     exact (MerchantSearchTimingTest pins 5,000 vectors under 50 ms);
--   - a plain index on the model column serves that filter.
-- Vectors of one model are compared only with vectors of the same model (every query filters on model, as before), so
-- a 768 and a 1024 vector are never compared. Every row stays: switching paths and back costs no re-embedding.

DROP INDEX ix_merchant_embedding_hnsw;
DROP INDEX ix_search_embedding_hnsw;

ALTER TABLE merchant_embedding DROP CONSTRAINT ck_merchant_embedding_dimensions;
ALTER TABLE merchant_embedding ALTER COLUMN embedding TYPE vector;
ALTER TABLE merchant_embedding ADD CONSTRAINT ck_merchant_embedding_dimensions
    CHECK (dimensions BETWEEN 1 AND 16000 AND vector_dims(embedding) = dimensions);
CREATE INDEX ix_merchant_embedding_model ON merchant_embedding (model);

ALTER TABLE search_embedding DROP CONSTRAINT ck_search_embedding_dimensions;
ALTER TABLE search_embedding ALTER COLUMN embedding TYPE vector;
ALTER TABLE search_embedding ADD CONSTRAINT ck_search_embedding_dimensions
    CHECK (dimensions BETWEEN 1 AND 16000 AND vector_dims(embedding) = dimensions);
CREATE INDEX ix_search_embedding_kind_model ON search_embedding (kind, model);

COMMENT ON COLUMN merchant_embedding.dimensions IS
    'The vector''s length (Titan V2 1024, nomic-embed-text 768); vector_dims(embedding) must equal it.';
COMMENT ON COLUMN search_embedding.dimensions IS
    'The vector''s length (Titan V2 1024, nomic-embed-text 768); vector_dims(embedding) must equal it.';

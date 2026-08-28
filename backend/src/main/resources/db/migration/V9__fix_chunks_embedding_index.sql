-- Fix: replace ivfflat index with HNSW.
--
-- The original V4 migration created:
--   CREATE INDEX idx_chunks_embedding ON chunks
--   USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
--
-- ivfflat is unreliable when a table has far fewer rows than the configured
-- 'lists' value: with 3 rows vs 100 lists, the closeness search probes mostly
-- empty lists and returns 0-1 results even though matching chunks exist.
-- HNSW performs exact-equivalent approximate search that works correctly for
-- both tiny and large datasets (requires pgvector >= 0.5, included in pg16 image).
DROP INDEX IF EXISTS idx_chunks_embedding;
CREATE INDEX idx_chunks_embedding ON chunks
    USING hnsw (embedding vector_cosine_ops);
CREATE TABLE staging_generations (
    generation_id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES indexing_jobs(id) ON DELETE CASCADE,
    worker_id VARCHAR(100) NOT NULL,
    cleanup_after TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_staging_generations_cleanup ON staging_generations(cleanup_after, generation_id);

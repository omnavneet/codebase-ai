-- Background indexing queue.
--
-- ZIP extraction and embedding used to run on the request thread, so an upload
-- blocked until the whole project was indexed. A row here lets the upload
-- endpoint return immediately (202) and hand the work to a worker process.
--
-- Claiming uses SELECT ... FOR UPDATE SKIP LOCKED (see IndexingJobRepository),
-- so several workers can poll the same table without ever taking one job twice.
-- A job that dies mid-flight is recovered because its row stays 'processing'
-- with a stale locked_at; the claim query treats that as claimable again.
CREATE TABLE indexing_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'queued',
    progress_percent INT NOT NULL DEFAULT 0,
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    next_run_at TIMESTAMP NOT NULL DEFAULT NOW(),
    locked_by VARCHAR(100),
    locked_at TIMESTAMP,
    error_message TEXT,
    -- SHA-256 of the uploaded ZIP: idempotency key for re-uploads of the same
    -- archive, so a retried/double submit does not enqueue duplicate work.
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- The worker's hot path: pick the next runnable job by (status, next_run_at).
CREATE INDEX idx_indexing_jobs_status_next_run ON indexing_jobs(status, next_run_at);
CREATE INDEX idx_indexing_jobs_project_id ON indexing_jobs(project_id);

ALTER TABLE staging_generations
    ADD COLUMN project_id UUID,
    ALTER COLUMN job_id DROP NOT NULL,
    ALTER COLUMN worker_id DROP NOT NULL,
    DROP CONSTRAINT staging_generations_job_id_fkey,
    ADD CONSTRAINT staging_generations_job_id_fkey
        FOREIGN KEY (job_id) REFERENCES indexing_jobs(id) ON DELETE SET NULL;

CREATE INDEX idx_staging_generations_job_id ON staging_generations(job_id);

-- Keep the storage address before the foreign key clears the job ID.
CREATE FUNCTION retain_staging_project_id() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    UPDATE staging_generations SET project_id = OLD.project_id WHERE job_id = OLD.id;
    RETURN OLD;
END;
$$;

CREATE TRIGGER retain_staging_project_id_before_delete
BEFORE DELETE ON indexing_jobs
FOR EACH ROW EXECUTE FUNCTION retain_staging_project_id();

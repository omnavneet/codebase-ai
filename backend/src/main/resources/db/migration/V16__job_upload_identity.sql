-- Existing in-flight jobs do not have a trustworthy link to a staged file.
-- The worker reports those jobs as requiring a re-upload instead of guessing.
ALTER TABLE indexing_jobs ADD COLUMN upload_id UUID;

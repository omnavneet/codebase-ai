ALTER TABLE projects ADD COLUMN active_generation_id UUID;
ALTER TABLE files ADD COLUMN generation_id UUID;
CREATE INDEX idx_files_project_generation ON files(project_id, generation_id);
CREATE TABLE published_generations (
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    generation_id UUID NOT NULL,
    published_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (project_id, generation_id)
);

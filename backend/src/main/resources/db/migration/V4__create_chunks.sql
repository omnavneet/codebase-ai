CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id UUID NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    token_count INT,
    embedding vector(384),
    start_line INT,
    end_line INT,
    chunk_type VARCHAR(50) DEFAULT 'code',
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_chunks_project_id ON chunks(project_id);
CREATE INDEX idx_chunks_file_id ON chunks(file_id);
CREATE INDEX idx_chunks_embedding ON chunks 
USING ivfflat (embedding vector_cosine_ops) 
WITH (lists = 100);
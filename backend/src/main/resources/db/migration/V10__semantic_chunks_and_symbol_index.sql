-- Semantic chunking + symbol index.
--
-- Files now record the detected language (used to pick the right parser and to
-- report unsupported languages honestly).
ALTER TABLE files ADD COLUMN language VARCHAR(32);

-- Chunks carry the symbol they belong to, so retrieval, citations and the agent
-- can name the function/class a chunk came from. chunk_type becomes meaningful:
-- function | method | class | interface | block | imports | module | part.
ALTER TABLE chunks ADD COLUMN symbol VARCHAR(255);
ALTER TABLE chunks ADD COLUMN parent_symbol VARCHAR(255);

CREATE TABLE code_symbols (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    file_id UUID NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    parent_symbol VARCHAR(255),
    start_line INT NOT NULL,
    end_line INT NOT NULL,
    signature VARCHAR(500),
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_code_symbols_project_name ON code_symbols(project_id, name);
CREATE INDEX idx_code_symbols_file_id ON code_symbols(file_id);

-- Call sites and imports exactly as written in the source. Resolution against
-- code_symbols happens at query time so it can never go stale; is_dynamic marks
-- references static analysis cannot bind (reflection, getattr, eval, computed
-- member calls) so callers can report them honestly instead of inventing edges.
CREATE TABLE code_references (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    file_id UUID NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    from_symbol VARCHAR(255),
    to_name VARCHAR(255) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    line INT NOT NULL,
    is_dynamic BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_code_references_project_to_name ON code_references(project_id, to_name);
CREATE INDEX idx_code_references_file_id ON code_references(file_id);

CREATE TABLE conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL DEFAULT 'New Chat',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- A message belongs to a conversation; session_id stays for legacy session-based
-- chats but is no longer mandatory.
ALTER TABLE chat_messages ADD COLUMN conversation_id UUID NULL REFERENCES conversations(id) ON DELETE CASCADE;
ALTER TABLE chat_messages ALTER COLUMN session_id DROP NOT NULL;

CREATE INDEX idx_conversations_project_id ON conversations(project_id);
CREATE INDEX idx_chat_messages_conversation_id ON chat_messages(conversation_id);

-- Backfill: every project that already has messages gets one "Earlier chat"
-- conversation and all of its messages are assigned to it.
WITH new_conversations AS (
    INSERT INTO conversations (project_id, title, created_at, updated_at)
    SELECT DISTINCT s.project_id, 'Earlier chat', NOW(), NOW()
    FROM chat_messages m
    JOIN chat_sessions s ON m.session_id = s.id
    WHERE m.conversation_id IS NULL
    GROUP BY s.project_id
    RETURNING id, project_id
)
UPDATE chat_messages m
SET conversation_id = new_conversations.id
FROM new_conversations
JOIN chat_sessions s ON m.session_id = s.id
WHERE s.project_id = new_conversations.project_id
  AND m.conversation_id IS NULL;
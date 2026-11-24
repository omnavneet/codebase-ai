ALTER TABLE chat_messages ADD COLUMN reply_to_message_id UUID REFERENCES chat_messages(id) ON DELETE CASCADE;
ALTER TABLE chat_messages ADD COLUMN run_metadata TEXT;
ALTER TABLE conversations ADD COLUMN history_revision BIGINT NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX idx_chat_messages_reply ON chat_messages(reply_to_message_id)
    WHERE reply_to_message_id IS NOT NULL;

CREATE TABLE chat_runs (
    request_id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_message_id UUID NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    mode VARCHAR(20) NOT NULL,
    question TEXT NOT NULL,
    regenerate BOOLEAN NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_id UUID NOT NULL,
    started_at TIMESTAMP NOT NULL,
    result_data TEXT,
    assistant_message_id UUID REFERENCES chat_messages(id) ON DELETE SET NULL
);
CREATE INDEX idx_chat_runs_conversation ON chat_runs(conversation_id);

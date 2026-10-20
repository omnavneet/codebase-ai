-- Running summary of a conversation's older messages, so that long chats stop
-- growing the prompt window sent to the model.
--
-- `summary` holds the condensed text (regenerated and extended as the chat
-- grows). `summary_through_message_id` marks the last message folded into the
-- summary; everything after it is still sent to the model as live history, so
-- the cutoff advances but never overtakes the recent history window.
--
-- The foreign key back to chat_messages keeps the pointer valid; ON DELETE SET
-- NULL keeps the column nullable if a message is ever removed.
ALTER TABLE conversations ADD COLUMN summary TEXT NULL;
ALTER TABLE conversations ADD COLUMN summary_through_message_id UUID NULL REFERENCES chat_messages(id) ON DELETE SET NULL;

CREATE INDEX idx_conversations_summary_through ON conversations(summary_through_message_id);

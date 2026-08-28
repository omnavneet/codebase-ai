-- Change chat_messages.citations from JSONB to TEXT to match the JPA entity
-- (ChatMessage.citations is a String mapped with columnDefinition = "TEXT")
ALTER TABLE chat_messages ALTER COLUMN citations TYPE TEXT USING citations::text;
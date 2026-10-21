-- Per-project "answer style" instructions for chat. The stored text is appended
-- to the chat prompt as a style-only hint; it can affect tone, length and
-- format but never project isolation, citation, tool or safety rules.
--
-- 1500 characters is enforced in the API layer (not the DB) so the constraint
-- can be tuned without a migration.
ALTER TABLE projects ADD COLUMN chat_instructions TEXT NULL;

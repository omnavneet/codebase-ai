-- Email verification: a new account is unusable (no login, no AI features) until the
-- address is confirmed through the link mailed at registration.
--
-- verification_token_hash stores the SHA-256 hash of the token, never the token itself.
-- NULL means "no pending token": it is cleared on successful verification and replaced
-- whenever a new token is issued (which is what makes the old link stop working).
ALTER TABLE users ADD COLUMN is_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN verification_token_hash VARCHAR(255);
ALTER TABLE users ADD COLUMN verification_token_expires_at TIMESTAMP;

-- Verification looks users up by token hash on every link click.
CREATE INDEX idx_users_verification_token_hash ON users(verification_token_hash);

-- Accounts created before this feature existed cannot be verified retroactively, so treat
-- them as verified instead of locking them out. No-op on a fresh (wiped) database.
UPDATE users SET is_verified = TRUE;

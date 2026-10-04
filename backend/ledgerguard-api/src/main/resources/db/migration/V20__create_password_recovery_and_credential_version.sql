-- ==============================================================================
-- LedgerGuard Flyway Migration V20: Password Recovery & Credential Version
-- ==============================================================================
-- 1. Add credential_version column to users table with DEFAULT 1 NOT NULL.
-- 2. Create password_reset_tokens table storing SHA-256 token hashes, single-use
--    consumed_at timestamp, expiration, and user reference.
-- ==============================================================================

-- 1. Alter users table to track credential version
ALTER TABLE users ADD COLUMN credential_version INT NOT NULL DEFAULT 1;

-- 2. Create password_reset_tokens table
CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT fk_password_reset_tokens_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_password_reset_tokens_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens(user_id);
CREATE INDEX idx_password_reset_tokens_expires_at ON password_reset_tokens(expires_at);

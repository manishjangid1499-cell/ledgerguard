-- ==============================================================================
-- LedgerGuard Flyway Migration V18: Add full_name column to users table
-- ==============================================================================

ALTER TABLE users ADD COLUMN full_name VARCHAR(120);

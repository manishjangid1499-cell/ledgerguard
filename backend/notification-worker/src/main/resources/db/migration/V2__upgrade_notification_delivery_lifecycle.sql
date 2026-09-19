-- ====================================================================
-- Notification Worker Database Schema - V2
-- Upgrades notification_deliveries with real delivery lifecycle states,
-- multi-recipient composite uniqueness, lease claiming, and retry metadata.
-- Preserves existing DELIVERED records by migrating them to LEGACY_RECORDED.
-- ====================================================================

-- 1. Drop the legacy check constraint restricting status to 'DELIVERED'
ALTER TABLE notification_deliveries
    DROP CONSTRAINT IF EXISTS chk_notification_deliveries_status;

-- 2. Migrate existing historical rows to LEGACY_RECORDED so they do not masquerade as SMTP deliveries
UPDATE notification_deliveries
SET status = 'LEGACY_RECORDED'
WHERE status = 'DELIVERED';

-- 3. Drop legacy unique constraint on event_id to allow multi-recipient delivery per event
ALTER TABLE notification_deliveries
    DROP CONSTRAINT IF EXISTS notification_deliveries_event_id_key;

-- 4. Add new columns for recipient metadata, delivery channels, templates, retry policies, and lease locking
ALTER TABLE notification_deliveries
    ADD COLUMN IF NOT EXISTS recipient_user_id UUID,
    ADD COLUMN IF NOT EXISTS recipient_email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS channel VARCHAR(32) NOT NULL DEFAULT 'EMAIL',
    ADD COLUMN IF NOT EXISTS template_key VARCHAR(64),
    ADD COLUMN IF NOT EXISTS subject VARCHAR(255),
    ADD COLUMN IF NOT EXISTS payload_json TEXT,
    ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_error TEXT,
    ADD COLUMN IF NOT EXISTS sent_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS locked_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- 5. Add new check constraint enforcing full delivery lifecycle status enum
ALTER TABLE notification_deliveries
    ADD CONSTRAINT chk_notification_deliveries_status
    CHECK (status IN ('PENDING', 'SENDING', 'RETRY_PENDING', 'SENT', 'FAILED', 'SKIPPED', 'LEGACY_RECORDED'));

-- 6. Add composite unique index to prevent duplicate deliveries per event, recipient, channel, and template
CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_deliveries_composite
    ON notification_deliveries (event_id, recipient_email, channel, template_key);

-- 7. Add performance index for Stage B dispatcher claiming (FOR UPDATE SKIP LOCKED)
CREATE INDEX IF NOT EXISTS idx_notif_deliveries_claim
    ON notification_deliveries (status, next_attempt_at, locked_until);

-- 8. Add index for querying deliveries by event_id
CREATE INDEX IF NOT EXISTS idx_notif_deliveries_event_id
    ON notification_deliveries (event_id);

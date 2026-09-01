-- ====================================================================
-- Notification Worker Database Schema - V1
-- Creates processed_events (inbox deduplication) and notification_deliveries tables.
-- ====================================================================

CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    event_version INTEGER NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_processed_events_event_type CHECK (TRIM(event_type) <> ''),
    CONSTRAINT chk_processed_events_event_version CHECK (event_version > 0),
    CONSTRAINT chk_processed_events_aggregate_type CHECK (TRIM(aggregate_type) <> '')
);

CREATE TABLE notification_deliveries (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DELIVERED',
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_notification_deliveries_event FOREIGN KEY (event_id)
        REFERENCES processed_events(event_id) ON DELETE RESTRICT,
    CONSTRAINT chk_notification_deliveries_event_type CHECK (TRIM(event_type) <> ''),
    CONSTRAINT chk_notification_deliveries_aggregate_type CHECK (TRIM(aggregate_type) <> ''),
    CONSTRAINT chk_notification_deliveries_status CHECK (status = 'DELIVERED')
);

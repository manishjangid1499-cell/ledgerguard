-- V9__create_outbox_events.sql
-- Phase 16: Transactional Outbox Persistence

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ NULL,
    CONSTRAINT chk_outbox_events_status_valid
        CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT chk_outbox_events_version_positive
        CHECK (event_version > 0),
    CONSTRAINT chk_outbox_events_aggregate_type_non_empty
        CHECK (trim(aggregate_type) <> ''),
    CONSTRAINT chk_outbox_events_event_type_non_empty
        CHECK (trim(event_type) <> ''),
    CONSTRAINT chk_outbox_events_payload_is_object
        CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT chk_outbox_events_status_published_at_consistency
        CHECK (
            (status = 'PENDING' AND published_at IS NULL) OR
            (status = 'PUBLISHED' AND published_at IS NOT NULL AND published_at >= created_at)
        ),
    CONSTRAINT chk_outbox_events_created_after_occurred
        CHECK (created_at >= occurred_at)
);

-- Partial index for high-efficiency polling of pending outbox events
CREATE INDEX idx_outbox_events_pending_created
    ON outbox_events(created_at, id)
    WHERE status = 'PENDING';

-- Trigger function enforcing outbox event lifecycle and data immutability
CREATE OR REPLACE FUNCTION trg_fn_enforce_outbox_events_integrity()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Initial insert must be PENDING with published_at NULL
        IF NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'Direct insert of non-PENDING outbox event is prohibited';
        END IF;

        IF NEW.published_at IS NOT NULL THEN
            RAISE EXCEPTION 'Direct insert with non-null published_at is prohibited';
        END IF;

        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        -- Event content is strictly immutable
        IF NEW.id <> OLD.id
           OR NEW.aggregate_type <> OLD.aggregate_type
           OR NEW.aggregate_id <> OLD.aggregate_id
           OR NEW.event_type <> OLD.event_type
           OR NEW.event_version <> OLD.event_version
           OR NEW.payload <> OLD.payload
           OR NEW.occurred_at <> OLD.occurred_at
           OR NEW.created_at <> OLD.created_at THEN
            RAISE EXCEPTION 'Outbox event data is immutable';
        END IF;

        -- Terminal PUBLISHED status is immutable
        IF OLD.status = 'PUBLISHED' THEN
            RAISE EXCEPTION 'Outbox events in PUBLISHED status are immutable';
        END IF;

        -- Legal transition: PENDING -> PUBLISHED
        IF OLD.status = 'PENDING' AND NEW.status = 'PUBLISHED' THEN
            IF NEW.published_at IS NULL OR NEW.published_at < OLD.created_at THEN
                RAISE EXCEPTION 'published_at must be set when transitioning to PUBLISHED';
            END IF;
            RETURN NEW;
        ELSE
            RAISE EXCEPTION 'Invalid status transition for outbox event: % to %', OLD.status, NEW.status;
        END IF;
    ELSIF TG_OP = 'DELETE' THEN
        -- Unpublished PENDING events must NEVER be deleted (loss of event intent)
        IF OLD.status = 'PENDING' THEN
            RAISE EXCEPTION 'Cannot delete outbox event in PENDING status';
        END IF;

        -- Deletion of PUBLISHED events is allowed for future cleanup/retention policies
        RETURN OLD;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_outbox_events_integrity
    BEFORE INSERT OR UPDATE OR DELETE ON outbox_events
    FOR EACH ROW
    EXECUTE FUNCTION trg_fn_enforce_outbox_events_integrity();

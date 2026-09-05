-- V17__add_outbox_trace_context.sql
-- Phase 30: OpenTelemetry Distributed Tracing & Correlation IDs Outbox Context

ALTER TABLE outbox_events
    ADD COLUMN traceparent VARCHAR(128) NULL,
    ADD COLUMN tracestate VARCHAR(512) NULL,
    ADD COLUMN correlation_id VARCHAR(64) NULL;

ALTER TABLE outbox_events
    ADD CONSTRAINT chk_outbox_events_traceparent_len
        CHECK (traceparent IS NULL OR length(traceparent) <= 128),
    ADD CONSTRAINT chk_outbox_events_correlation_id_len
        CHECK (correlation_id IS NULL OR length(correlation_id) <= 64);

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
           OR NEW.created_at <> OLD.created_at
           OR NEW.traceparent IS DISTINCT FROM OLD.traceparent
           OR NEW.tracestate IS DISTINCT FROM OLD.tracestate
           OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
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
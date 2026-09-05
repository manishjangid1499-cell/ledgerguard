-- ==============================================================================
-- LedgerGuard Flyway Migration V16: Immutable Audit Trail Schema
-- ==============================================================================

CREATE TABLE audit_events (
    id             UUID        PRIMARY KEY,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_user_id  UUID        NOT NULL,
    action         VARCHAR(64) NOT NULL,
    target_type    VARCHAR(64) NOT NULL,
    target_id      UUID        NOT NULL,
    details        JSONB,

    CONSTRAINT fk_audit_events_actor_user_id
        FOREIGN KEY (actor_user_id)
        REFERENCES users(id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_audit_events_action
        CHECK (action IN (
            'RECONCILIATION_CASE_CLAIMED',
            'RECONCILIATION_SNAPSHOT_REPAIRED',
            'RECONCILIATION_ALREADY_CONSISTENT',
            'RECONCILIATION_CASE_MANUALLY_RESOLVED'
        )),

    CONSTRAINT chk_audit_events_target_type
        CHECK (target_type IN ('RECONCILIATION_CASE'))
);

CREATE INDEX idx_audit_events_actor_user_id ON audit_events(actor_user_id);
CREATE INDEX idx_audit_events_target ON audit_events(target_type, target_id);
CREATE INDEX idx_audit_events_occurred_at ON audit_events(occurred_at DESC);

-- Trigger function enforcing row-level immutability (UPDATE and DELETE prohibited)
CREATE OR REPLACE FUNCTION trg_fn_enforce_audit_events_immutability()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'audit_events is append-only: UPDATE is strictly prohibited';
    ELSIF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'audit_events is append-only: DELETE is strictly prohibited';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_events_no_update_delete
BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW
EXECUTE FUNCTION trg_fn_enforce_audit_events_immutability();

-- Trigger function enforcing table-level immutability (TRUNCATE prohibited)
CREATE OR REPLACE FUNCTION trg_fn_enforce_audit_events_no_truncate()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only: TRUNCATE is strictly prohibited';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_events_no_truncate
BEFORE TRUNCATE ON audit_events
FOR EACH STATEMENT
EXECUTE FUNCTION trg_fn_enforce_audit_events_no_truncate();
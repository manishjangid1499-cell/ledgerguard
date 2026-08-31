-- Flyway V4: Create idempotency records table, scope uniqueness constraint, and immutability triggers

-- 1. Idempotency Records Table
CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL,
    operation VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_idempotency_records_actor_user_id
        FOREIGN KEY (actor_user_id)
        REFERENCES users(id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_idempotency_records_scope
        UNIQUE (actor_user_id, operation, idempotency_key),
    CONSTRAINT chk_idempotency_records_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT chk_idempotency_records_status_fields
        CHECK (
            (status = 'IN_PROGRESS' AND result_id IS NULL AND completed_at IS NULL) OR
            (status = 'COMPLETED' AND result_id IS NOT NULL AND completed_at IS NOT NULL)
        ),
    CONSTRAINT chk_idempotency_records_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_idempotency_records_key
        CHECK (length(trim(idempotency_key)) > 0 AND length(idempotency_key) <= 128),
    CONSTRAINT chk_idempotency_records_operation
        CHECK (length(trim(operation)) > 0 AND length(operation) <= 64)
);

-- 2. PostgreSQL Invariant Enforcement Function & Trigger
CREATE OR REPLACE FUNCTION trg_fn_enforce_idempotency_record_immutability()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'IN_PROGRESS' THEN
            RAISE EXCEPTION 'Idempotency records must be inserted with status IN_PROGRESS. Direct insertion of status % is forbidden', NEW.status;
        END IF;
        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.status = 'COMPLETED' THEN
            RAISE EXCEPTION 'Completed idempotency record % is immutable and cannot be updated', OLD.id;
        END IF;
        IF OLD.status = 'IN_PROGRESS' AND NEW.status <> 'COMPLETED' THEN
            RAISE EXCEPTION 'Invalid status transition for idempotency record %: % to %', OLD.id, OLD.status, NEW.status;
        END IF;
        IF OLD.id <> NEW.id OR
           OLD.actor_user_id <> NEW.actor_user_id OR
           OLD.operation <> NEW.operation OR
           OLD.idempotency_key <> NEW.idempotency_key OR
           OLD.request_fingerprint <> NEW.request_fingerprint OR
           OLD.created_at <> NEW.created_at THEN
            RAISE EXCEPTION 'Immutable fields of idempotency record % cannot be modified', OLD.id;
        END IF;
        IF NEW.result_id IS NULL OR NEW.completed_at IS NULL THEN
            RAISE EXCEPTION 'Completed idempotency record % must have non-null result_id and completed_at', OLD.id;
        END IF;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        IF OLD.status = 'COMPLETED' THEN
            RAISE EXCEPTION 'Completed idempotency record % is immutable and cannot be deleted', OLD.id;
        END IF;
        RETURN OLD;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_idempotency_records_immutability
BEFORE INSERT OR UPDATE OR DELETE ON idempotency_records
FOR EACH ROW
EXECUTE FUNCTION trg_fn_enforce_idempotency_record_immutability();

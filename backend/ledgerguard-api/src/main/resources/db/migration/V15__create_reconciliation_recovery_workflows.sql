-- ==============================================================================
-- LedgerGuard Flyway Migration V15: Reconciliation Recovery & Manual Review Workflows
-- ==============================================================================

-- 1. Create reconciliation_cases table
CREATE TABLE reconciliation_cases (
    id                      UUID        PRIMARY KEY,
    reconciliation_item_id  UUID        NOT NULL UNIQUE,
    status                  VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    assigned_to_user_id     UUID,
    resolved_by_user_id     UUID,
    resolution_action       VARCHAR(64),
    resolution_note         VARCHAR(1000),
    opened_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at             TIMESTAMPTZ,

    CONSTRAINT fk_recon_cases_item
        FOREIGN KEY (reconciliation_item_id)
        REFERENCES reconciliation_items(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_recon_cases_assigned_to
        FOREIGN KEY (assigned_to_user_id)
        REFERENCES users(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_recon_cases_resolved_by
        FOREIGN KEY (resolved_by_user_id)
        REFERENCES users(id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_recon_cases_status
        CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED')),

    CONSTRAINT chk_recon_cases_action
        CHECK (resolution_action IS NULL OR resolution_action IN ('SNAPSHOT_REPAIRED', 'ALREADY_CONSISTENT', 'MANUAL_REVIEW_COMPLETED')),

    CONSTRAINT chk_recon_cases_open_state
        CHECK (status <> 'OPEN' OR (
            assigned_to_user_id IS NULL AND
            resolved_by_user_id IS NULL AND
            resolved_at IS NULL AND
            resolution_action IS NULL AND
            resolution_note IS NULL
        )),

    CONSTRAINT chk_recon_cases_in_review_state
        CHECK (status <> 'IN_REVIEW' OR (
            assigned_to_user_id IS NOT NULL AND
            resolved_by_user_id IS NULL AND
            resolved_at IS NULL AND
            resolution_action IS NULL
        )),

    CONSTRAINT chk_recon_cases_resolved_state
        CHECK (status <> 'RESOLVED' OR (
            resolved_by_user_id IS NOT NULL AND
            resolved_at IS NOT NULL AND
            resolution_action IS NOT NULL
        )),

    CONSTRAINT chk_recon_cases_manual_review_note
        CHECK (resolution_action <> 'MANUAL_REVIEW_COMPLETED' OR (
            resolution_note IS NOT NULL AND
            LENGTH(TRIM(resolution_note)) > 0
        )),

    CONSTRAINT chk_recon_cases_timestamps
        CHECK (
            updated_at >= opened_at AND
            (resolved_at IS NULL OR resolved_at >= opened_at)
        ),

    CONSTRAINT chk_recon_cases_note_length
        CHECK (resolution_note IS NULL OR LENGTH(resolution_note) <= 1000)
);

CREATE INDEX idx_recon_cases_status      ON reconciliation_cases(status);
CREATE INDEX idx_recon_cases_assigned_to ON reconciliation_cases(assigned_to_user_id);
CREATE INDEX idx_recon_cases_opened_at   ON reconciliation_cases(opened_at DESC, id DESC);

-- 2. Trigger enforcing reconciliation_cases lifecycle, immutability, and null-safe reassignment rules
CREATE OR REPLACE FUNCTION trg_fn_enforce_recon_case_lifecycle()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' THEN
            RAISE EXCEPTION
                'reconciliation_cases must be inserted with status OPEN. Got: %', NEW.status;
        END IF;
        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN
        -- Identity and opened timestamp immutability
        IF NEW.id <> OLD.id OR NEW.reconciliation_item_id <> OLD.reconciliation_item_id OR NEW.opened_at <> OLD.opened_at THEN
            RAISE EXCEPTION
                'Immutable fields of reconciliation_case % cannot be modified', OLD.id;
        END IF;

        -- Terminal immutability
        IF OLD.status = 'RESOLVED' THEN
            RAISE EXCEPTION
                'Terminal reconciliation_case % cannot be updated', OLD.id;
        END IF;

        -- Null-safe reassignment / unassignment prevention
        IF OLD.assigned_to_user_id IS NOT NULL
           AND NEW.assigned_to_user_id IS DISTINCT FROM OLD.assigned_to_user_id THEN
            RAISE EXCEPTION
                'reconciliation_case % cannot be reassigned or unassigned once claimed', OLD.id;
        END IF;

        -- Lifecycle status transitions
        IF OLD.status = 'OPEN' AND NEW.status NOT IN ('OPEN', 'IN_REVIEW', 'RESOLVED') THEN
            RAISE EXCEPTION
                'Invalid status transition for reconciliation_case %: OPEN -> %',
                OLD.id, NEW.status;
        ELSIF OLD.status = 'IN_REVIEW' AND NEW.status NOT IN ('IN_REVIEW', 'RESOLVED') THEN
            RAISE EXCEPTION
                'Invalid status transition for reconciliation_case %: IN_REVIEW -> %',
                OLD.id, NEW.status;
        END IF;

        RETURN NEW;

    ELSIF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'reconciliation_case % cannot be deleted', OLD.id;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_recon_cases_lifecycle
BEFORE INSERT OR UPDATE OR DELETE ON reconciliation_cases
FOR EACH ROW EXECUTE FUNCTION trg_fn_enforce_recon_case_lifecycle();

-- 3. Backfill existing historical reconciliation items into OPEN cases
INSERT INTO reconciliation_cases (id, reconciliation_item_id, status, opened_at, updated_at)
SELECT
    gen_random_uuid(),
    ri.id,
    'OPEN',
    ri.detected_at,
    ri.detected_at
FROM reconciliation_items ri
ON CONFLICT (reconciliation_item_id) DO NOTHING;

-- 4. Trigger on reconciliation_items to auto-create an OPEN case for future detected items
CREATE OR REPLACE FUNCTION trg_fn_auto_create_recon_case()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO reconciliation_cases (id, reconciliation_item_id, status, opened_at, updated_at)
    VALUES (gen_random_uuid(), NEW.id, 'OPEN', NEW.detected_at, NEW.detected_at)
    ON CONFLICT (reconciliation_item_id) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_recon_items_auto_create_case
AFTER INSERT ON reconciliation_items
FOR EACH ROW EXECUTE FUNCTION trg_fn_auto_create_recon_case();

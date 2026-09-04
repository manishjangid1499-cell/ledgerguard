-- ==============================================================================
-- LedgerGuard Flyway Migration V14: Core Reconciliation Engine
-- ==============================================================================

-- 1. reconciliation_runs table
CREATE TABLE reconciliation_runs (
    id                  UUID        PRIMARY KEY,
    status              VARCHAR(32) NOT NULL,
    trigger_source      VARCHAR(32) NOT NULL,
    started_at          TIMESTAMPTZ NOT NULL,
    completed_at        TIMESTAMPTZ,
    journals_checked    BIGINT      NOT NULL DEFAULT 0,
    accounts_checked    BIGINT      NOT NULL DEFAULT 0,
    operations_checked  BIGINT      NOT NULL DEFAULT 0,
    discrepancy_count   BIGINT      NOT NULL DEFAULT 0,
    unresolved_count    BIGINT      NOT NULL DEFAULT 0,
    failure_reason      TEXT,

    CONSTRAINT chk_recon_runs_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_recon_runs_trigger
        CHECK (trigger_source IN ('SCHEDULED', 'ON_DEMAND')),
    CONSTRAINT chk_recon_runs_completed_at CHECK (
        (status = 'RUNNING'                AND completed_at IS NULL) OR
        (status IN ('COMPLETED', 'FAILED') AND completed_at IS NOT NULL)
    ),
    CONSTRAINT chk_recon_runs_counts_non_negative CHECK (
        journals_checked   >= 0 AND accounts_checked   >= 0 AND
        operations_checked >= 0 AND discrepancy_count  >= 0 AND
        unresolved_count   >= 0
    )
);

CREATE INDEX idx_recon_runs_status     ON reconciliation_runs(status);
CREATE INDEX idx_recon_runs_started_at ON reconciliation_runs(started_at DESC);

-- 2. reconciliation_items table
CREATE TABLE reconciliation_items (
    id                    UUID        PRIMARY KEY,
    reconciliation_run_id UUID        NOT NULL,
    classification        VARCHAR(16) NOT NULL,
    level                 VARCHAR(32) NOT NULL,
    problem_type          VARCHAR(64) NOT NULL,
    entity_type           VARCHAR(32) NOT NULL,
    entity_id             UUID        NOT NULL,
    observed_local_status VARCHAR(32),
    expected_value        NUMERIC,
    actual_value          NUMERIC,
    provider_status       VARCHAR(32),
    description           TEXT        NOT NULL,
    detected_at           TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_recon_items_run
        FOREIGN KEY (reconciliation_run_id)
        REFERENCES reconciliation_runs(id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_recon_items_classification
        CHECK (classification IN ('DISCREPANCY', 'UNRESOLVED')),

    CONSTRAINT chk_recon_items_level
        CHECK (level IN (
            'JOURNAL_BALANCE',
            'SNAPSHOT_CONSISTENCY',
            'PROVIDER_SETTLEMENT'
        )),

    CONSTRAINT chk_recon_items_problem_type
        CHECK (problem_type IN (
            'UNBALANCED_JOURNAL',
            'MALFORMED_JOURNAL',
            'SNAPSHOT_MISMATCH',
            'SNAPSHOT_MISSING',
            'PROVIDER_STATUS_MISMATCH',
            'PROVIDER_IDENTITY_MISMATCH',
            'PROVIDER_NOT_FOUND',
            'PROVIDER_UNAVAILABLE',
            'PROVIDER_STILL_PROCESSING'
        )),

    CONSTRAINT chk_recon_items_entity_type
        CHECK (entity_type IN (
            'JOURNAL_TRANSACTION',
            'LEDGER_ACCOUNT',
            'FUNDING_OPERATION',
            'PAYOUT'
        )),

    -- level <-> entity_type consistency
    CONSTRAINT chk_recon_items_level_entity CHECK (
        (level = 'JOURNAL_BALANCE'      AND entity_type = 'JOURNAL_TRANSACTION') OR
        (level = 'SNAPSHOT_CONSISTENCY' AND entity_type = 'LEDGER_ACCOUNT') OR
        (level = 'PROVIDER_SETTLEMENT'  AND entity_type IN ('FUNDING_OPERATION', 'PAYOUT'))
    ),

    -- level <-> problem_type consistency
    CONSTRAINT chk_recon_items_level_problem CHECK (
        (level = 'JOURNAL_BALANCE'      AND problem_type IN ('UNBALANCED_JOURNAL', 'MALFORMED_JOURNAL')) OR
        (level = 'SNAPSHOT_CONSISTENCY' AND problem_type IN ('SNAPSHOT_MISMATCH', 'SNAPSHOT_MISSING')) OR
        (level = 'PROVIDER_SETTLEMENT'  AND problem_type IN (
            'PROVIDER_STATUS_MISMATCH',
            'PROVIDER_IDENTITY_MISMATCH',
            'PROVIDER_NOT_FOUND',
            'PROVIDER_UNAVAILABLE',
            'PROVIDER_STILL_PROCESSING'
        ))
    ),

    -- classification <-> problem_type consistency
    -- DISCREPANCY: confirmed financial inconsistency
    -- UNRESOLVED: ambiguous/unavailable provider observation
    -- PROVIDER_NOT_FOUND is permitted for BOTH (depends on local lifecycle state)
    CONSTRAINT chk_recon_items_classification_problem CHECK (
        (classification = 'DISCREPANCY' AND problem_type IN (
            'UNBALANCED_JOURNAL',
            'MALFORMED_JOURNAL',
            'SNAPSHOT_MISMATCH',
            'SNAPSHOT_MISSING',
            'PROVIDER_STATUS_MISMATCH',
            'PROVIDER_IDENTITY_MISMATCH',
            'PROVIDER_NOT_FOUND'
        )) OR
        (classification = 'UNRESOLVED' AND problem_type IN (
            'PROVIDER_UNAVAILABLE',
            'PROVIDER_STILL_PROCESSING',
            'PROVIDER_NOT_FOUND'
        ))
    ),

    -- JOURNAL_BALANCE items are always DISCREPANCY
    CONSTRAINT chk_recon_items_journal_always_discrepancy CHECK (
        level <> 'JOURNAL_BALANCE' OR classification = 'DISCREPANCY'
    ),

    -- SNAPSHOT_CONSISTENCY items are always DISCREPANCY
    CONSTRAINT chk_recon_items_snapshot_always_discrepancy CHECK (
        level <> 'SNAPSHOT_CONSISTENCY' OR classification = 'DISCREPANCY'
    )
);

CREATE INDEX idx_recon_items_run_id   ON reconciliation_items(reconciliation_run_id);
CREATE INDEX idx_recon_items_level    ON reconciliation_items(reconciliation_run_id, level);
CREATE INDEX idx_recon_items_entity   ON reconciliation_items(entity_type, entity_id);
CREATE INDEX idx_recon_items_detected ON reconciliation_items(detected_at DESC);

-- 3. reconciliation_runs lifecycle trigger
CREATE OR REPLACE FUNCTION trg_fn_enforce_recon_run_lifecycle()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'RUNNING' THEN
            RAISE EXCEPTION
                'reconciliation_runs must be inserted with status RUNNING. Got: %', NEW.status;
        END IF;
        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.status IN ('COMPLETED', 'FAILED') THEN
            RAISE EXCEPTION
                'Terminal reconciliation_run % cannot be updated', OLD.id;
        END IF;
        IF NEW.status NOT IN ('COMPLETED', 'FAILED') THEN
            RAISE EXCEPTION
                'Invalid status transition for reconciliation_run %: RUNNING -> %',
                OLD.id, NEW.status;
        END IF;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'reconciliation_run % cannot be deleted', OLD.id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_recon_runs_lifecycle
BEFORE INSERT OR UPDATE OR DELETE ON reconciliation_runs
FOR EACH ROW EXECUTE FUNCTION trg_fn_enforce_recon_run_lifecycle();

-- 4. reconciliation_items append-only trigger with run-status guard
CREATE OR REPLACE FUNCTION trg_fn_enforce_recon_item_immutability()
RETURNS TRIGGER AS $$
DECLARE
    v_run_status VARCHAR(32);
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Acquire shared lock on parent run to serialize with run finalization.
        -- If finalization already holds FOR UPDATE and committed terminal status,
        -- this FOR SHARE sees terminal and rejects. If finalization is in-flight,
        -- this waits, then checks again after commit.
        SELECT status INTO v_run_status
        FROM reconciliation_runs
        WHERE id = NEW.reconciliation_run_id
        FOR SHARE;

        IF v_run_status IS NULL THEN
            RAISE EXCEPTION
                'reconciliation_run % referenced by item does not exist',
                NEW.reconciliation_run_id;
        END IF;

        IF v_run_status <> 'RUNNING' THEN
            RAISE EXCEPTION
                'Cannot insert reconciliation_item: parent run % is in terminal status %',
                NEW.reconciliation_run_id, v_run_status;
        END IF;

        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION
            'reconciliation_item % is immutable and cannot be updated', OLD.id;

    ELSIF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'reconciliation_item % is immutable and cannot be deleted', OLD.id;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_recon_items_immutability
BEFORE INSERT OR UPDATE OR DELETE ON reconciliation_items
FOR EACH ROW EXECUTE FUNCTION trg_fn_enforce_recon_item_immutability();

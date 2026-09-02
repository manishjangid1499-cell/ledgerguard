-- ==============================================================================
-- LedgerGuard Flyway Migration V12: Provider Events Inbox, Ordering & Invariants
-- ==============================================================================

-- 1. Create provider_events table
CREATE TABLE provider_events (
    event_id UUID PRIMARY KEY,
    provider_operation_id UUID NOT NULL,
    client_operation_id UUID NOT NULL,
    event_sequence BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    provider_status VARCHAR(32) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    processing_status VARCHAR(32) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NULL,
    CONSTRAINT chk_provider_events_sequence CHECK (event_sequence > 0),
    CONSTRAINT chk_provider_events_amount CHECK (amount_minor > 0),
    CONSTRAINT chk_provider_events_currency CHECK (currency = 'INR'),
    CONSTRAINT chk_provider_events_operation_type CHECK (operation_type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT chk_provider_events_provider_status CHECK (provider_status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_provider_events_event_type CHECK (event_type IN (
        'PROVIDER_OPERATION_PROCESSING',
        'PROVIDER_OPERATION_SUCCEEDED',
        'PROVIDER_OPERATION_FAILED'
    )),
    CONSTRAINT chk_provider_events_type_status_match CHECK (
        (event_type = 'PROVIDER_OPERATION_PROCESSING' AND provider_status = 'PROCESSING') OR
        (event_type = 'PROVIDER_OPERATION_SUCCEEDED' AND provider_status = 'SUCCEEDED') OR
        (event_type = 'PROVIDER_OPERATION_FAILED' AND provider_status = 'FAILED')
    ),
    CONSTRAINT chk_provider_events_payload_json CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT chk_provider_events_processing_status CHECK (processing_status IN ('PENDING', 'APPLIED', 'IGNORED')),
    CONSTRAINT chk_provider_events_processed_at CHECK (
        (processing_status = 'PENDING' AND processed_at IS NULL) OR
        (processing_status IN ('APPLIED', 'IGNORED') AND processed_at IS NOT NULL)
    ),
    CONSTRAINT uq_provider_events_op_seq UNIQUE (provider_operation_id, event_sequence)
);

-- 2. PostgreSQL Trigger Function enforcing lifecycle and immutability
CREATE OR REPLACE FUNCTION trg_fn_enforce_provider_events_immutability()
RETURNS TRIGGER AS $$
BEGIN
    -- Disallow row deletion
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ProviderEvent deletion is strictly forbidden: event_id=%', OLD.event_id;
    END IF;

    -- Enforce initial state on INSERT
    IF TG_OP = 'INSERT' THEN
        IF NEW.processing_status <> 'PENDING' THEN
            RAISE EXCEPTION 'ProviderEvent insert must have processing_status = PENDING: event_id=%, status=%',
                NEW.event_id, NEW.processing_status;
        END IF;
        IF NEW.processed_at IS NOT NULL THEN
            RAISE EXCEPTION 'ProviderEvent insert must have processed_at IS NULL: event_id=%', NEW.event_id;
        END IF;
        RETURN NEW;
    END IF;

    -- Enforce immutability and valid status transitions on UPDATE
    IF TG_OP = 'UPDATE' THEN
        -- Immutable business content check
        IF NEW.event_id <> OLD.event_id OR
           NEW.provider_operation_id <> OLD.provider_operation_id OR
           NEW.client_operation_id <> OLD.client_operation_id OR
           NEW.event_sequence <> OLD.event_sequence OR
           NEW.event_type <> OLD.event_type OR
           NEW.operation_type <> OLD.operation_type OR
           NEW.provider_status <> OLD.provider_status OR
           NEW.amount_minor <> OLD.amount_minor OR
           NEW.currency <> OLD.currency OR
           NEW.occurred_at <> OLD.occurred_at OR
           NEW.payload <> OLD.payload OR
           NEW.received_at <> OLD.received_at THEN
            RAISE EXCEPTION 'ProviderEvent business content is immutable: event_id=%', OLD.event_id;
        END IF;

        -- Processing lifecycle transition check: only PENDING -> APPLIED or PENDING -> IGNORED
        IF OLD.processing_status = 'PENDING' THEN
            IF NEW.processing_status NOT IN ('APPLIED', 'IGNORED') THEN
                RAISE EXCEPTION 'Invalid ProviderEvent processing status transition: % -> % for event_id=%',
                    OLD.processing_status, NEW.processing_status, OLD.event_id;
            END IF;
            IF NEW.processed_at IS NULL THEN
                RAISE EXCEPTION 'ProviderEvent transition to % requires non-null processed_at: event_id=%',
                    NEW.processing_status, OLD.event_id;
            END IF;
        ELSE
            -- Terminal processing status cannot transition further
            IF NEW.processing_status <> OLD.processing_status OR NEW.processed_at <> OLD.processed_at THEN
                RAISE EXCEPTION 'ProviderEvent terminal status % cannot be modified: event_id=%',
                    OLD.processing_status, OLD.event_id;
            END IF;
        END IF;

        RETURN NEW;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 3. Attach trigger to provider_events table
CREATE TRIGGER trg_provider_events_immutability
BEFORE INSERT OR UPDATE OR DELETE ON provider_events
FOR EACH ROW
EXECUTE FUNCTION trg_fn_enforce_provider_events_immutability();

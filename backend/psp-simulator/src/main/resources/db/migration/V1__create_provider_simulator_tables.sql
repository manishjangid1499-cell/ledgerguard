-- ====================================================================
-- LedgerGuard PSP Simulator — V1 Migration
-- Tables: provider_operations, provider_webhooks
-- ====================================================================

CREATE TABLE provider_operations (
    id UUID PRIMARY KEY,
    client_operation_id UUID NOT NULL UNIQUE,
    operation_type VARCHAR(16) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    scenario VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT chk_provider_operations_type CHECK (operation_type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT chk_provider_operations_amount CHECK (amount_minor > 0),
    CONSTRAINT chk_provider_operations_currency CHECK (currency = 'INR'),
    CONSTRAINT chk_provider_operations_status CHECK (status = 'SUCCEEDED'),
    CONSTRAINT chk_provider_operations_scenario CHECK (scenario IN ('NORMAL_SUCCESS', 'TIMEOUT_AFTER_SUCCESS', 'DELAYED_WEBHOOK', 'DUPLICATE_WEBHOOK', 'TEMPORARY_500')),
    CONSTRAINT chk_provider_operations_completion CHECK (status = 'SUCCEEDED' AND completed_at IS NOT NULL)
);

CREATE TABLE provider_webhooks (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    provider_operation_id UUID NOT NULL REFERENCES provider_operations(id) ON DELETE RESTRICT,
    delivery_number INTEGER NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    target_url TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_provider_webhooks_event_delivery UNIQUE (event_id, delivery_number),
    CONSTRAINT chk_provider_webhooks_delivery_number CHECK (delivery_number > 0),
    CONSTRAINT chk_provider_webhooks_event_type CHECK (TRIM(event_type) <> ''),
    CONSTRAINT chk_provider_webhooks_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT chk_provider_webhooks_target_url CHECK (TRIM(target_url) <> ''),
    CONSTRAINT chk_provider_webhooks_status CHECK (status IN ('SCHEDULED', 'DELIVERED', 'FAILED')),
    CONSTRAINT chk_provider_webhooks_delivered_at CHECK (
        (status = 'DELIVERED' AND delivered_at IS NOT NULL) OR
        (status <> 'DELIVERED' AND delivered_at IS NULL)
    )
);

CREATE INDEX idx_provider_webhooks_scheduled
ON provider_webhooks (scheduled_at, id)
WHERE status = 'SCHEDULED';

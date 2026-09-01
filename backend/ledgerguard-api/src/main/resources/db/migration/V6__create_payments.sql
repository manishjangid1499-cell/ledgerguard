-- Flyway V6: Create payments table, constraints, and lifecycle/immutability triggers

-- 1. Payments Table
CREATE TABLE payments (
    id UUID PRIMARY KEY,
    customer_user_id UUID NOT NULL,
    customer_ledger_account_id UUID NOT NULL,
    merchant_ledger_account_id UUID NOT NULL,
    gross_amount_minor BIGINT NOT NULL,
    fee_amount_minor BIGINT NOT NULL,
    merchant_net_amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    journal_transaction_id UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_payments_customer_user_id
        FOREIGN KEY (customer_user_id)
        REFERENCES users(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_payments_customer_ledger_account_id
        FOREIGN KEY (customer_ledger_account_id)
        REFERENCES ledger_accounts(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_payments_merchant_ledger_account_id
        FOREIGN KEY (merchant_ledger_account_id)
        REFERENCES ledger_accounts(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_payments_journal_transaction_id
        FOREIGN KEY (journal_transaction_id)
        REFERENCES journal_transactions(id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_payments_gross_positive
        CHECK (gross_amount_minor > 0),
    CONSTRAINT chk_payments_fee_non_negative
        CHECK (fee_amount_minor >= 0),
    CONSTRAINT chk_payments_net_positive
        CHECK (merchant_net_amount_minor > 0),
    CONSTRAINT chk_payments_fee_less_gross
        CHECK (fee_amount_minor < gross_amount_minor),
    CONSTRAINT chk_payments_net_balance
        CHECK (merchant_net_amount_minor = (gross_amount_minor - fee_amount_minor)),
    CONSTRAINT chk_payments_currency_inr
        CHECK (currency = 'INR'),
    CONSTRAINT chk_payments_distinct_accounts
        CHECK (customer_ledger_account_id <> merchant_ledger_account_id),
    CONSTRAINT chk_payments_status_valid
        CHECK (status IN ('CREATED', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_payments_status_fields
        CHECK (
            (status = 'CREATED' AND journal_transaction_id IS NULL AND completed_at IS NULL) OR
            (status = 'PROCESSING' AND journal_transaction_id IS NULL AND completed_at IS NULL) OR
            (status = 'SUCCEEDED' AND journal_transaction_id IS NOT NULL AND completed_at IS NOT NULL) OR
            (status = 'FAILED' AND journal_transaction_id IS NULL AND completed_at IS NOT NULL)
        )
);

-- 2. Lifecycle & Immutability Trigger Function
CREATE OR REPLACE FUNCTION trg_fn_enforce_payments_lifecycle_and_immutability()
RETURNS TRIGGER AS $$
DECLARE
    v_journal_status VARCHAR(32);
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'CREATED' THEN
            RAISE EXCEPTION 'Payments must be inserted with status CREATED. Direct insertion of status % is forbidden', NEW.status;
        END IF;
        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        -- Check terminal status immutability
        IF OLD.status = 'SUCCEEDED' THEN
            RAISE EXCEPTION 'Payment % with terminal status SUCCEEDED is immutable and cannot be updated', OLD.id;
        END IF;
        IF OLD.status = 'FAILED' THEN
            RAISE EXCEPTION 'Payment % with terminal status FAILED is immutable and cannot be updated', OLD.id;
        END IF;

        -- Check immutable identity and business fields
        IF OLD.id <> NEW.id OR
           OLD.customer_user_id <> NEW.customer_user_id OR
           OLD.customer_ledger_account_id <> NEW.customer_ledger_account_id OR
           OLD.merchant_ledger_account_id <> NEW.merchant_ledger_account_id OR
           OLD.gross_amount_minor <> NEW.gross_amount_minor OR
           OLD.fee_amount_minor <> NEW.fee_amount_minor OR
           OLD.merchant_net_amount_minor <> NEW.merchant_net_amount_minor OR
           OLD.currency <> NEW.currency OR
           OLD.created_at <> NEW.created_at THEN
            RAISE EXCEPTION 'Immutable business fields of Payment % cannot be modified', OLD.id;
        END IF;

        -- Valid transitions:
        -- CREATED -> PROCESSING
        -- CREATED -> FAILED
        -- PROCESSING -> SUCCEEDED
        -- PROCESSING -> FAILED
        IF OLD.status = 'CREATED' THEN
            IF NEW.status NOT IN ('PROCESSING', 'FAILED') THEN
                RAISE EXCEPTION 'Invalid status transition for Payment %: % to %', OLD.id, OLD.status, NEW.status;
            END IF;
        ELSIF OLD.status = 'PROCESSING' THEN
            IF NEW.status NOT IN ('SUCCEEDED', 'FAILED') THEN
                RAISE EXCEPTION 'Invalid status transition for Payment %: % to %', OLD.id, OLD.status, NEW.status;
            END IF;
        ELSE
            RAISE EXCEPTION 'Invalid status transition for Payment %: % to %', OLD.id, OLD.status, NEW.status;
        END IF;

        -- When transitioning to SUCCEEDED, verify referenced journal_transaction_id exists and is POSTED
        IF NEW.status = 'SUCCEEDED' THEN
            IF NEW.journal_transaction_id IS NULL THEN
                RAISE EXCEPTION 'SUCCEEDED Payment % must reference a journal transaction', NEW.id;
            END IF;

            SELECT status INTO v_journal_status
            FROM journal_transactions
            WHERE id = NEW.journal_transaction_id;

            IF v_journal_status IS NULL THEN
                RAISE EXCEPTION 'Referenced journal transaction % does not exist', NEW.journal_transaction_id;
            END IF;

            IF v_journal_status <> 'POSTED' THEN
                RAISE EXCEPTION 'Referenced journal transaction % must be POSTED, but was %', NEW.journal_transaction_id, v_journal_status;
            END IF;
        END IF;

        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Payment % is immutable and cannot be deleted', OLD.id;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payments_lifecycle_and_immutability
BEFORE INSERT OR UPDATE OR DELETE ON payments
FOR EACH ROW
EXECUTE FUNCTION trg_fn_enforce_payments_lifecycle_and_immutability();

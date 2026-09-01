-- Flyway V7: Create refunds table, index, and integrity/cumulative-cap trigger

-- 1. Refunds Table
CREATE TABLE refunds (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    initiated_by_user_id UUID NOT NULL,
    refund_amount_minor BIGINT NOT NULL,
    merchant_debit_amount_minor BIGINT NOT NULL,
    fee_debit_amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    journal_transaction_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_refunds_payment_id
        FOREIGN KEY (payment_id)
        REFERENCES payments(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_refunds_initiated_by_user_id
        FOREIGN KEY (initiated_by_user_id)
        REFERENCES users(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_refunds_journal_transaction_id
        FOREIGN KEY (journal_transaction_id)
        REFERENCES journal_transactions(id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_refunds_amount_positive
        CHECK (refund_amount_minor > 0),
    CONSTRAINT chk_refunds_merchant_debit_non_negative
        CHECK (merchant_debit_amount_minor >= 0),
    CONSTRAINT chk_refunds_fee_debit_non_negative
        CHECK (fee_debit_amount_minor >= 0),
    CONSTRAINT chk_refunds_amount_balance
        CHECK (refund_amount_minor = (merchant_debit_amount_minor + fee_debit_amount_minor)),
    CONSTRAINT chk_refunds_currency_inr
        CHECK (currency = 'INR')
);

-- 2. Index on payment_id for cumulative refund lookups
CREATE INDEX idx_refunds_payment_id ON refunds(payment_id);

-- 3. Integrity, Immutability & Cumulative Cap Trigger Function
CREATE OR REPLACE FUNCTION trg_fn_enforce_refunds_integrity()
RETURNS TRIGGER AS $$
DECLARE
    v_payment_gross NUMERIC;
    v_payment_status VARCHAR(32);
    v_already_refunded NUMERIC;
    v_journal_status VARCHAR(20);
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Refunds are immutable and cannot be updated';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Refunds are immutable and cannot be deleted';
    END IF;

    IF TG_OP = 'INSERT' THEN
        -- 1. Verify referenced journal exists and is POSTED
        SELECT status INTO v_journal_status
        FROM journal_transactions
        WHERE id = NEW.journal_transaction_id;

        IF v_journal_status IS NULL THEN
            RAISE EXCEPTION 'Referenced journal transaction % does not exist', NEW.journal_transaction_id;
        END IF;

        IF v_journal_status <> 'POSTED' THEN
            RAISE EXCEPTION 'Refund must reference a POSTED journal transaction, but journal % has status %',
                NEW.journal_transaction_id, v_journal_status;
        END IF;

        -- 2. Lock parent payment row FOR UPDATE to serialize concurrent inserts and check status
        SELECT gross_amount_minor, status INTO v_payment_gross, v_payment_status
        FROM payments
        WHERE id = NEW.payment_id
        FOR UPDATE;

        IF v_payment_gross IS NULL THEN
            RAISE EXCEPTION 'Referenced payment % does not exist', NEW.payment_id;
        END IF;

        IF v_payment_status <> 'SUCCEEDED' THEN
            RAISE EXCEPTION 'Refund can only be created for SUCCEEDED payments, but payment % has status %',
                NEW.payment_id, v_payment_status;
        END IF;

        -- 3. Calculate already refunded gross amount under payment row lock
        SELECT COALESCE(SUM(refund_amount_minor), 0) INTO v_already_refunded
        FROM refunds
        WHERE payment_id = NEW.payment_id;

        IF (v_already_refunded + NEW.refund_amount_minor) > v_payment_gross THEN
            RAISE EXCEPTION 'Cumulative refund amount % exceeds payment gross amount % for payment %',
                (v_already_refunded + NEW.refund_amount_minor), v_payment_gross, NEW.payment_id;
        END IF;

        RETURN NEW;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_refunds_integrity
BEFORE INSERT OR UPDATE OR DELETE ON refunds
FOR EACH ROW
EXECUTE FUNCTION trg_fn_enforce_refunds_integrity();

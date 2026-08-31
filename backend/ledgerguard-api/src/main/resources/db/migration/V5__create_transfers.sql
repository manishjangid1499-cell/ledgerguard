-- Flyway V5: Create transfers table, constraints, and immutability trigger

-- 1. Transfers Table
CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    initiated_by_user_id UUID NOT NULL,
    source_ledger_account_id UUID NOT NULL,
    destination_ledger_account_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    journal_transaction_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_transfers_initiated_by_user_id
        FOREIGN KEY (initiated_by_user_id)
        REFERENCES users(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_transfers_source_ledger_account_id
        FOREIGN KEY (source_ledger_account_id)
        REFERENCES ledger_accounts(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_transfers_destination_ledger_account_id
        FOREIGN KEY (destination_ledger_account_id)
        REFERENCES ledger_accounts(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_transfers_journal_transaction_id
        FOREIGN KEY (journal_transaction_id)
        REFERENCES journal_transactions(id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_transfers_amount_positive
        CHECK (amount_minor > 0),
    CONSTRAINT chk_transfers_currency_inr
        CHECK (currency = 'INR'),
    CONSTRAINT chk_transfers_distinct_accounts
        CHECK (source_ledger_account_id <> destination_ledger_account_id)
);

-- 2. Immutability Trigger Function & Trigger
CREATE OR REPLACE FUNCTION trg_fn_enforce_transfers_immutability()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Transfer record % is immutable and cannot be updated', OLD.id;
    ELSIF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Transfer record % is immutable and cannot be deleted', OLD.id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_transfers_immutability
BEFORE UPDATE OR DELETE ON transfers
FOR EACH ROW
EXECUTE FUNCTION trg_fn_enforce_transfers_immutability();

-- 3. Validate Referenced Journal Transaction is POSTED on Insert
CREATE OR REPLACE FUNCTION trg_fn_validate_transfer_journal_posted()
RETURNS TRIGGER AS $$
DECLARE
    v_status VARCHAR(20);
BEGIN
    SELECT status INTO v_status
    FROM journal_transactions
    WHERE id = NEW.journal_transaction_id;

    IF v_status IS NULL THEN
        RAISE EXCEPTION 'Referenced journal transaction % does not exist', NEW.journal_transaction_id;
    END IF;

    IF v_status <> 'POSTED' THEN
        RAISE EXCEPTION 'Referenced journal transaction % must be POSTED, but was %', NEW.journal_transaction_id, v_status;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_transfers_validate_journal_posted
BEFORE INSERT ON transfers
FOR EACH ROW
EXECUTE FUNCTION trg_fn_validate_transfer_journal_posted();


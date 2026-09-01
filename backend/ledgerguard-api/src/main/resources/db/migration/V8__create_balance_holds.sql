-- Flyway V8: Create balance_holds table, partial indexes, and integrity/capacity triggers

-- 1. Balance Holds Table
CREATE TABLE balance_holds (
    id UUID PRIMARY KEY,
    ledger_account_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    terminal_at TIMESTAMPTZ NULL,
    CONSTRAINT fk_balance_holds_ledger_account_id
        FOREIGN KEY (ledger_account_id)
        REFERENCES ledger_accounts(id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_balance_holds_amount_positive
        CHECK (amount_minor > 0),
    CONSTRAINT chk_balance_holds_currency_inr
        CHECK (currency = 'INR'),
    CONSTRAINT chk_balance_holds_status_valid
        CHECK (status IN ('ACTIVE', 'CONSUMED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT chk_balance_holds_expires_after_created
        CHECK (expires_at > created_at),
    CONSTRAINT chk_balance_holds_updated_after_created
        CHECK (updated_at >= created_at),
    CONSTRAINT chk_balance_holds_terminal_state_consistency
        CHECK (
            (status = 'ACTIVE' AND terminal_at IS NULL) OR
            (status IN ('CONSUMED', 'RELEASED', 'EXPIRED') AND terminal_at IS NOT NULL AND terminal_at >= created_at)
        )
);

-- 2. Partial Indexes
CREATE INDEX idx_balance_holds_account_active ON balance_holds(ledger_account_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_balance_holds_expires_active ON balance_holds(expires_at) WHERE status = 'ACTIVE';

-- 3. Integrity, Immutability & Capacity Trigger Function
CREATE OR REPLACE FUNCTION trg_fn_enforce_balance_holds_integrity()
RETURNS TRIGGER AS $$
DECLARE
    v_account_type VARCHAR(32);
    v_account_status VARCHAR(20);
    v_account_currency CHAR(3);
    v_owner_user_id UUID;
    v_posted_balance NUMERIC;
    v_active_holds NUMERIC;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Balance holds are immutable and cannot be deleted';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        -- Immutable fields check
        IF OLD.id <> NEW.id OR
           OLD.ledger_account_id <> NEW.ledger_account_id OR
           OLD.amount_minor <> NEW.amount_minor OR
           OLD.currency <> NEW.currency OR
           OLD.expires_at <> NEW.expires_at OR
           OLD.created_at <> NEW.created_at THEN
            RAISE EXCEPTION 'Identity and reservation fields of balance_holds are immutable';
        END IF;

        -- Status transition check
        IF OLD.status <> NEW.status THEN
            IF OLD.status IN ('CONSUMED', 'RELEASED', 'EXPIRED') THEN
                RAISE EXCEPTION 'Cannot transition from terminal hold status % to %', OLD.status, NEW.status;
            END IF;

            IF OLD.status = 'ACTIVE' AND NEW.status NOT IN ('CONSUMED', 'RELEASED', 'EXPIRED') THEN
                RAISE EXCEPTION 'Invalid hold status transition from ACTIVE to %', NEW.status;
            END IF;
        END IF;

        RETURN NEW;
    END IF;

    IF TG_OP = 'INSERT' THEN
        -- Status on insert MUST be ACTIVE
        IF NEW.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'Initial balance hold status must be ACTIVE, found: %', NEW.status;
        END IF;

        -- Account validation
        SELECT account_type, status, currency, owner_user_id
        INTO v_account_type, v_account_status, v_account_currency, v_owner_user_id
        FROM ledger_accounts
        WHERE id = NEW.ledger_account_id;

        IF v_account_type IS NULL THEN
            RAISE EXCEPTION 'Referenced ledger account % does not exist', NEW.ledger_account_id;
        END IF;

        IF v_account_status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'Balance holds can only be created for ACTIVE ledger accounts, but account % is %',
                NEW.ledger_account_id, v_account_status;
        END IF;

        IF v_account_type NOT IN ('CUSTOMER', 'MERCHANT') OR v_owner_user_id IS NULL THEN
            RAISE EXCEPTION 'Balance holds are only permitted for user wallet accounts (CUSTOMER or MERCHANT), account % is %',
                NEW.ledger_account_id, v_account_type;
        END IF;

        IF v_account_currency <> 'INR' THEN
            RAISE EXCEPTION 'Balance holds only support INR currency, account % is %',
                NEW.ledger_account_id, v_account_currency;
        END IF;

        -- Capacity check: Lock parent snapshot row FOR UPDATE
        SELECT balance_minor INTO v_posted_balance
        FROM ledger_balance_snapshots
        WHERE ledger_account_id = NEW.ledger_account_id
        FOR UPDATE;

        IF v_posted_balance IS NULL THEN
            RAISE EXCEPTION 'Balance snapshot missing for ledger account %', NEW.ledger_account_id;
        END IF;

        -- Sum existing active holds under the locked snapshot
        SELECT COALESCE(SUM(amount_minor), 0) INTO v_active_holds
        FROM balance_holds
        WHERE ledger_account_id = NEW.ledger_account_id
          AND status = 'ACTIVE';

        IF (v_active_holds + NEW.amount_minor::NUMERIC) > v_posted_balance THEN
            RAISE EXCEPTION 'Insufficient available balance for hold on account %: requested hold %, active holds %, posted balance %',
                NEW.ledger_account_id, NEW.amount_minor, v_active_holds, v_posted_balance;
        END IF;

        RETURN NEW;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_balance_holds_integrity
BEFORE INSERT OR UPDATE OR DELETE ON balance_holds
FOR EACH ROW
EXECUTE FUNCTION trg_fn_enforce_balance_holds_integrity();

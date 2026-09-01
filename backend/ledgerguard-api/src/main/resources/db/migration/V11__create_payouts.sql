-- ==============================================================================
-- LedgerGuard Flyway Migration V11: Create payouts table and triggers
-- ==============================================================================

-- 1. Payouts Table
CREATE TABLE payouts (
    id UUID PRIMARY KEY,
    initiated_by_user_id UUID NOT NULL,
    source_ledger_account_id UUID NOT NULL,
    balance_hold_id UUID NOT NULL UNIQUE,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    provider_operation_id UUID UNIQUE,
    journal_transaction_id UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_payouts_initiated_by
        FOREIGN KEY (initiated_by_user_id)
        REFERENCES users(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_payouts_source_account
        FOREIGN KEY (source_ledger_account_id)
        REFERENCES ledger_accounts(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_payouts_balance_hold
        FOREIGN KEY (balance_hold_id)
        REFERENCES balance_holds(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_payouts_journal_transaction
        FOREIGN KEY (journal_transaction_id)
        REFERENCES journal_transactions(id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_payouts_amount_minor
        CHECK (amount_minor > 0),
    CONSTRAINT chk_payouts_currency_inr
        CHECK (currency = 'INR'),
    CONSTRAINT chk_payouts_status_valid
        CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_payouts_status_fields
        CHECK (
            (status = 'PROCESSING' AND provider_operation_id IS NULL AND journal_transaction_id IS NULL AND completed_at IS NULL) OR
            (status = 'SUCCEEDED' AND provider_operation_id IS NOT NULL AND journal_transaction_id IS NOT NULL AND completed_at IS NOT NULL) OR
            (status = 'FAILED' AND journal_transaction_id IS NULL AND completed_at IS NOT NULL)
        )
);

-- 2. Lifecycle & Immutability Trigger Function
CREATE OR REPLACE FUNCTION trg_fn_enforce_payouts_lifecycle_and_immutability()
RETURNS TRIGGER AS $$
DECLARE
    v_account_type VARCHAR(32);
    v_account_status VARCHAR(32);
    v_account_currency VARCHAR(3);
    v_account_owner UUID;
    v_hold_status VARCHAR(16);
    v_hold_account_id UUID;
    v_hold_amount BIGINT;
    v_hold_currency VARCHAR(3);
    v_journal_status VARCHAR(32);
    v_debit_count INT;
    v_credit_count INT;
    v_total_count INT;
    v_debit_account_id UUID;
    v_debit_amount BIGINT;
    v_credit_account_id UUID;
    v_credit_amount BIGINT;
    v_clearing_account_type VARCHAR(32);
    v_clearing_account_status VARCHAR(32);
    v_clearing_account_currency VARCHAR(3);
    v_clearing_account_owner UUID;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROCESSING' THEN
            RAISE EXCEPTION 'Payouts must be inserted with status PROCESSING. Direct insertion of status % is forbidden', NEW.status;
        END IF;

        -- Validate referenced source ledger account
        SELECT account_type, status, currency, owner_user_id
        INTO v_account_type, v_account_status, v_account_currency, v_account_owner
        FROM ledger_accounts
        WHERE id = NEW.source_ledger_account_id;

        IF v_account_type IS NULL THEN
            RAISE EXCEPTION 'Referenced source ledger account % does not exist', NEW.source_ledger_account_id;
        END IF;

        IF v_account_type NOT IN ('CUSTOMER', 'MERCHANT') THEN
            RAISE EXCEPTION 'Referenced source ledger account % must be of type CUSTOMER or MERCHANT, but was %', NEW.source_ledger_account_id, v_account_type;
        END IF;

        IF v_account_status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'Referenced source ledger account % must be ACTIVE, but was %', NEW.source_ledger_account_id, v_account_status;
        END IF;

        IF v_account_currency <> 'INR' THEN
            RAISE EXCEPTION 'Referenced source ledger account % currency must be INR, but was %', NEW.source_ledger_account_id, v_account_currency;
        END IF;

        IF v_account_owner <> NEW.initiated_by_user_id THEN
            RAISE EXCEPTION 'Source ledger account % owner % does not match initiator %', NEW.source_ledger_account_id, v_account_owner, NEW.initiated_by_user_id;
        END IF;

        -- Validate referenced balance hold
        SELECT status, ledger_account_id, amount_minor, currency
        INTO v_hold_status, v_hold_account_id, v_hold_amount, v_hold_currency
        FROM balance_holds
        WHERE id = NEW.balance_hold_id;

        IF v_hold_status IS NULL THEN
            RAISE EXCEPTION 'Referenced balance hold % does not exist', NEW.balance_hold_id;
        END IF;

        IF v_hold_status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'Referenced balance hold % must be ACTIVE on payout insert, but was %', NEW.balance_hold_id, v_hold_status;
        END IF;

        IF v_hold_account_id <> NEW.source_ledger_account_id THEN
            RAISE EXCEPTION 'Referenced balance hold % account % does not match payout source account %', NEW.balance_hold_id, v_hold_account_id, NEW.source_ledger_account_id;
        END IF;

        IF v_hold_amount <> NEW.amount_minor THEN
            RAISE EXCEPTION 'Referenced balance hold % amount % does not match payout amount %', NEW.balance_hold_id, v_hold_amount, NEW.amount_minor;
        END IF;

        IF v_hold_currency <> NEW.currency THEN
            RAISE EXCEPTION 'Referenced balance hold % currency % does not match payout currency %', NEW.balance_hold_id, v_hold_currency, NEW.currency;
        END IF;

        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        -- Check terminal status immutability
        IF OLD.status IN ('SUCCEEDED', 'FAILED') THEN
            RAISE EXCEPTION 'Payout % with terminal status % is immutable and cannot be updated', OLD.id, OLD.status;
        END IF;

        -- Verify immutable business identity fields cannot be modified
        IF OLD.id <> NEW.id OR
           OLD.initiated_by_user_id <> NEW.initiated_by_user_id OR
           OLD.source_ledger_account_id <> NEW.source_ledger_account_id OR
           OLD.balance_hold_id <> NEW.balance_hold_id OR
           OLD.amount_minor <> NEW.amount_minor OR
           OLD.currency <> NEW.currency OR
           OLD.created_at <> NEW.created_at THEN
            RAISE EXCEPTION 'Immutable fields of Payout % cannot be modified', OLD.id;
        END IF;

        -- Handle transition to SUCCEEDED
        IF NEW.status = 'SUCCEEDED' THEN
            IF NEW.provider_operation_id IS NULL THEN
                RAISE EXCEPTION 'Payout % transitioning to SUCCEEDED must have provider_operation_id populated', NEW.id;
            END IF;

            IF NEW.completed_at IS NULL THEN
                RAISE EXCEPTION 'Payout % transitioning to SUCCEEDED must have completed_at populated', NEW.id;
            END IF;

            IF NEW.journal_transaction_id IS NULL THEN
                RAISE EXCEPTION 'Payout % transitioning to SUCCEEDED must reference a journal_transaction_id', NEW.id;
            END IF;

            -- Validate linked hold is CONSUMED
            SELECT status INTO v_hold_status
            FROM balance_holds
            WHERE id = NEW.balance_hold_id;

            IF v_hold_status <> 'CONSUMED' THEN
                RAISE EXCEPTION 'Linked balance hold % must be CONSUMED on payout SUCCEEDED transition, but was %', NEW.balance_hold_id, v_hold_status;
            END IF;

            -- Validate settlement journal transaction
            SELECT status INTO v_journal_status
            FROM journal_transactions
            WHERE id = NEW.journal_transaction_id;

            IF v_journal_status IS NULL THEN
                RAISE EXCEPTION 'Referenced journal transaction % does not exist', NEW.journal_transaction_id;
            END IF;

            IF v_journal_status <> 'POSTED' THEN
                RAISE EXCEPTION 'Referenced journal transaction % must be POSTED, but was %', NEW.journal_transaction_id, v_journal_status;
            END IF;

            -- Validate journal entry count and structure
            SELECT count(*) INTO v_total_count
            FROM journal_entries
            WHERE journal_transaction_id = NEW.journal_transaction_id;

            IF v_total_count <> 2 THEN
                RAISE EXCEPTION 'Payout settlement journal % must have exactly 2 entries, found %', NEW.journal_transaction_id, v_total_count;
            END IF;

            -- Validate DEBIT entry (source wallet)
            SELECT count(*) INTO v_debit_count
            FROM journal_entries
            WHERE journal_transaction_id = NEW.journal_transaction_id
              AND direction = 'DEBIT';

            IF v_debit_count <> 1 THEN
                RAISE EXCEPTION 'Payout settlement journal % must have exactly 1 DEBIT entry, found %', NEW.journal_transaction_id, v_debit_count;
            END IF;

            SELECT ledger_account_id, amount_minor
            INTO v_debit_account_id, v_debit_amount
            FROM journal_entries
            WHERE journal_transaction_id = NEW.journal_transaction_id
              AND direction = 'DEBIT';

            IF v_debit_account_id <> NEW.source_ledger_account_id THEN
                RAISE EXCEPTION 'Payout settlement journal % DEBIT account % does not match source wallet %', NEW.journal_transaction_id, v_debit_account_id, NEW.source_ledger_account_id;
            END IF;

            IF v_debit_amount <> NEW.amount_minor THEN
                RAISE EXCEPTION 'Payout settlement journal % DEBIT amount % does not match payout amount %', NEW.journal_transaction_id, v_debit_amount, NEW.amount_minor;
            END IF;

            -- Validate CREDIT entry (PSP_CLEARING)
            SELECT count(*) INTO v_credit_count
            FROM journal_entries
            WHERE journal_transaction_id = NEW.journal_transaction_id
              AND direction = 'CREDIT';

            IF v_credit_count <> 1 THEN
                RAISE EXCEPTION 'Payout settlement journal % must have exactly 1 CREDIT entry, found %', NEW.journal_transaction_id, v_credit_count;
            END IF;

            SELECT ledger_account_id, amount_minor
            INTO v_credit_account_id, v_credit_amount
            FROM journal_entries
            WHERE journal_transaction_id = NEW.journal_transaction_id
              AND direction = 'CREDIT';

            IF v_credit_amount <> NEW.amount_minor THEN
                RAISE EXCEPTION 'Payout settlement journal % CREDIT amount % does not match payout amount %', NEW.journal_transaction_id, v_credit_amount, NEW.amount_minor;
            END IF;

            -- Validate credit account is an active INR PSP_CLEARING account with owner_user_id IS NULL
            SELECT account_type, status, currency, owner_user_id
            INTO v_clearing_account_type, v_clearing_account_status, v_clearing_account_currency, v_clearing_account_owner
            FROM ledger_accounts
            WHERE id = v_credit_account_id;

            IF v_clearing_account_type <> 'PSP_CLEARING' THEN
                RAISE EXCEPTION 'Payout settlement journal % CREDIT account % must be of type PSP_CLEARING, but was %', NEW.journal_transaction_id, v_credit_account_id, v_clearing_account_type;
            END IF;

            IF v_clearing_account_status <> 'ACTIVE' THEN
                RAISE EXCEPTION 'Payout settlement journal % CREDIT account % must be ACTIVE, but was %', NEW.journal_transaction_id, v_credit_account_id, v_clearing_account_status;
            END IF;

            IF v_clearing_account_currency <> 'INR' THEN
                RAISE EXCEPTION 'Payout settlement journal % CREDIT account % currency must be INR, but was %', NEW.journal_transaction_id, v_credit_account_id, v_clearing_account_currency;
            END IF;

            IF v_clearing_account_owner IS NOT NULL THEN
                RAISE EXCEPTION 'Payout settlement journal % CREDIT account % must be system-owned (owner null), but had owner %', NEW.journal_transaction_id, v_credit_account_id, v_clearing_account_owner;
            END IF;

        ELSIF NEW.status = 'FAILED' THEN
            IF NEW.completed_at IS NULL THEN
                RAISE EXCEPTION 'Payout % transitioning to FAILED must have completed_at populated', NEW.id;
            END IF;

            IF NEW.journal_transaction_id IS NOT NULL THEN
                RAISE EXCEPTION 'Payout % transitioning to FAILED must have journal_transaction_id NULL', NEW.id;
            END IF;

            -- Validate linked hold is RELEASED
            SELECT status INTO v_hold_status
            FROM balance_holds
            WHERE id = NEW.balance_hold_id;

            IF v_hold_status <> 'RELEASED' THEN
                RAISE EXCEPTION 'Linked balance hold % must be RELEASED on payout FAILED transition, but was %', NEW.balance_hold_id, v_hold_status;
            END IF;
        END IF;

        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Payouts are immutable and cannot be deleted';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- 3. Create Trigger on payouts table
CREATE TRIGGER trg_payouts_lifecycle_and_immutability
    BEFORE INSERT OR UPDATE OR DELETE ON payouts
    FOR EACH ROW
    EXECUTE FUNCTION trg_fn_enforce_payouts_lifecycle_and_immutability();

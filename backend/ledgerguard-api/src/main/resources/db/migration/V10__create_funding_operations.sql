-- ==============================================================================
-- LedgerGuard Flyway Migration V10: Create funding_operations table and triggers
-- ==============================================================================

-- 1. Funding Operations Table
CREATE TABLE funding_operations (
    id UUID PRIMARY KEY,
    initiated_by_user_id UUID NOT NULL,
    customer_ledger_account_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    provider_operation_id UUID UNIQUE,
    journal_transaction_id UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_funding_operations_initiated_by
        FOREIGN KEY (initiated_by_user_id)
        REFERENCES users(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_funding_operations_customer_account
        FOREIGN KEY (customer_ledger_account_id)
        REFERENCES ledger_accounts(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_funding_operations_journal_transaction
        FOREIGN KEY (journal_transaction_id)
        REFERENCES journal_transactions(id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_funding_operations_amount_minor
        CHECK (amount_minor > 0),
    CONSTRAINT chk_funding_operations_currency_inr
        CHECK (currency = 'INR'),
    CONSTRAINT chk_funding_operations_status_valid
        CHECK (status IN ('PROCESSING', 'SUCCEEDED')),
    CONSTRAINT chk_funding_operations_status_fields
        CHECK (
            (status = 'PROCESSING' AND provider_operation_id IS NULL AND journal_transaction_id IS NULL AND completed_at IS NULL) OR
            (status = 'SUCCEEDED' AND provider_operation_id IS NOT NULL AND journal_transaction_id IS NOT NULL AND completed_at IS NOT NULL)
        )
);

-- 2. Lifecycle & Immutability Trigger Function
CREATE OR REPLACE FUNCTION trg_fn_enforce_funding_operations_lifecycle_and_immutability()
RETURNS TRIGGER AS $$
DECLARE
    v_account_type VARCHAR(32);
    v_account_status VARCHAR(32);
    v_account_currency VARCHAR(3);
    v_account_owner UUID;
    v_journal_status VARCHAR(32);
    v_journal_currency VARCHAR(3);
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
            RAISE EXCEPTION 'Funding operations must be inserted with status PROCESSING. Direct insertion of status % is forbidden', NEW.status;
        END IF;

        -- Validate referenced customer ledger account
        SELECT account_type, status, currency, owner_user_id
        INTO v_account_type, v_account_status, v_account_currency, v_account_owner
        FROM ledger_accounts
        WHERE id = NEW.customer_ledger_account_id;

        IF v_account_type IS NULL THEN
            RAISE EXCEPTION 'Referenced customer ledger account % does not exist', NEW.customer_ledger_account_id;
        END IF;

        IF v_account_type <> 'CUSTOMER' THEN
            RAISE EXCEPTION 'Referenced ledger account % must be of type CUSTOMER, but was %', NEW.customer_ledger_account_id, v_account_type;
        END IF;

        IF v_account_status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'Referenced customer ledger account % must be ACTIVE, but was %', NEW.customer_ledger_account_id, v_account_status;
        END IF;

        IF v_account_currency <> 'INR' THEN
            RAISE EXCEPTION 'Referenced customer ledger account % currency must be INR, but was %', NEW.customer_ledger_account_id, v_account_currency;
        END IF;

        IF v_account_owner <> NEW.initiated_by_user_id THEN
            RAISE EXCEPTION 'Customer ledger account % owner % does not match initiator %', NEW.customer_ledger_account_id, v_account_owner, NEW.initiated_by_user_id;
        END IF;

        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        -- Check terminal status immutability
        IF OLD.status = 'SUCCEEDED' THEN
            RAISE EXCEPTION 'FundingOperation % with terminal status SUCCEEDED is immutable and cannot be updated', OLD.id;
        END IF;

        -- Check immutable identity and business fields
        IF OLD.id <> NEW.id OR
           OLD.initiated_by_user_id <> NEW.initiated_by_user_id OR
           OLD.customer_ledger_account_id <> NEW.customer_ledger_account_id OR
           OLD.amount_minor <> NEW.amount_minor OR
           OLD.currency <> NEW.currency OR
           OLD.created_at <> NEW.created_at THEN
            RAISE EXCEPTION 'Immutable business fields of FundingOperation % cannot be modified', OLD.id;
        END IF;

        -- Valid transitions: PROCESSING -> SUCCEEDED
        IF OLD.status = 'PROCESSING' THEN
            IF NEW.status <> 'SUCCEEDED' THEN
                RAISE EXCEPTION 'Invalid status transition for FundingOperation %: % to %', OLD.id, OLD.status, NEW.status;
            END IF;
        ELSE
            RAISE EXCEPTION 'Invalid status transition for FundingOperation %: % to %', OLD.id, OLD.status, NEW.status;
        END IF;

        -- When transitioning to SUCCEEDED, verify referenced journal_transaction_id exists, is POSTED, and represents exact settlement
        IF NEW.status = 'SUCCEEDED' THEN
            IF NEW.provider_operation_id IS NULL THEN
                RAISE EXCEPTION 'SUCCEEDED FundingOperation % must reference a provider_operation_id', NEW.id;
            END IF;

            IF NEW.journal_transaction_id IS NULL THEN
                RAISE EXCEPTION 'SUCCEEDED FundingOperation % must reference a journal transaction', NEW.id;
            END IF;

            IF NEW.completed_at IS NULL THEN
                RAISE EXCEPTION 'SUCCEEDED FundingOperation % must have completed_at timestamp', NEW.id;
            END IF;

            SELECT status, currency INTO v_journal_status, v_journal_currency
            FROM journal_transactions
            WHERE id = NEW.journal_transaction_id;

            IF v_journal_status IS NULL THEN
                RAISE EXCEPTION 'Referenced journal transaction % does not exist', NEW.journal_transaction_id;
            END IF;

            IF v_journal_status <> 'POSTED' THEN
                RAISE EXCEPTION 'Referenced journal transaction % must be POSTED, but was %', NEW.journal_transaction_id, v_journal_status;
            END IF;

            -- Inspect journal entries for exact funding settlement
            SELECT
                COUNT(*) FILTER (WHERE direction = 'DEBIT'),
                COUNT(*) FILTER (WHERE direction = 'CREDIT'),
                COUNT(*)
            INTO
                v_debit_count,
                v_credit_count,
                v_total_count
            FROM journal_entries
            WHERE journal_transaction_id = NEW.journal_transaction_id;

            IF v_total_count <> 2 OR v_debit_count <> 1 OR v_credit_count <> 1 THEN
                RAISE EXCEPTION 'Settlement journal transaction % must contain exactly 1 debit and 1 credit entry (found % entries)', NEW.journal_transaction_id, v_total_count;
            END IF;

            SELECT ledger_account_id, amount_minor
            INTO v_debit_account_id, v_debit_amount
            FROM journal_entries
            WHERE journal_transaction_id = NEW.journal_transaction_id AND direction = 'DEBIT';

            SELECT ledger_account_id, amount_minor
            INTO v_credit_account_id, v_credit_amount
            FROM journal_entries
            WHERE journal_transaction_id = NEW.journal_transaction_id AND direction = 'CREDIT';

            IF v_debit_amount <> NEW.amount_minor OR v_credit_amount <> NEW.amount_minor THEN
                RAISE EXCEPTION 'Settlement journal entries amounts (% debit, % credit) do not match funding amount %', v_debit_amount, v_credit_amount, NEW.amount_minor;
            END IF;

            IF v_credit_account_id <> NEW.customer_ledger_account_id THEN
                RAISE EXCEPTION 'Settlement journal credit account % does not match funding customer account %', v_credit_account_id, NEW.customer_ledger_account_id;
            END IF;

            -- Verify debit account is active INR PSP_CLEARING with owner_user_id NULL
            SELECT account_type, status, currency, owner_user_id
            INTO v_clearing_account_type, v_clearing_account_status, v_clearing_account_currency, v_clearing_account_owner
            FROM ledger_accounts
            WHERE id = v_debit_account_id;

            IF v_clearing_account_type <> 'PSP_CLEARING' OR
               v_clearing_account_status <> 'ACTIVE' OR
               v_clearing_account_currency <> 'INR' OR
               v_clearing_account_owner IS NOT NULL THEN
                RAISE EXCEPTION 'Settlement journal debit account % must be an active INR PSP_CLEARING account with no owner', v_debit_account_id;
            END IF;
        END IF;

        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'FundingOperation % is immutable and cannot be deleted', OLD.id;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_funding_operations_lifecycle_and_immutability
BEFORE INSERT OR UPDATE OR DELETE ON funding_operations
FOR EACH ROW
EXECUTE FUNCTION trg_fn_enforce_funding_operations_lifecycle_and_immutability();

-- ==============================================================================
-- LedgerGuard Flyway Migration V13: External State Machines & Status Recovery
-- ==============================================================================

-- 1. Add poll metadata columns to funding_operations and payouts
ALTER TABLE funding_operations
    ADD COLUMN provider_poll_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN next_provider_poll_at TIMESTAMPTZ,
    ADD COLUMN unknown_since TIMESTAMPTZ;

ALTER TABLE payouts
    ADD COLUMN provider_poll_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN next_provider_poll_at TIMESTAMPTZ,
    ADD COLUMN unknown_since TIMESTAMPTZ;

-- 2. Enforce non-negative poll attempts
ALTER TABLE funding_operations
    ADD CONSTRAINT chk_funding_operations_poll_attempts_non_negative
        CHECK (provider_poll_attempts >= 0);

ALTER TABLE payouts
    ADD CONSTRAINT chk_payouts_poll_attempts_non_negative
        CHECK (provider_poll_attempts >= 0);

-- 3. Backfill existing Phase 22 PROCESSING rows for immediate status recovery
UPDATE funding_operations
SET next_provider_poll_at = CURRENT_TIMESTAMP
WHERE status = 'PROCESSING'
  AND next_provider_poll_at IS NULL;

UPDATE payouts
SET next_provider_poll_at = CURRENT_TIMESTAMP
WHERE status = 'PROCESSING'
  AND next_provider_poll_at IS NULL;

-- 4. Update CHECK constraints on funding_operations
ALTER TABLE funding_operations
    DROP CONSTRAINT chk_funding_operations_status_valid,
    DROP CONSTRAINT chk_funding_operations_status_fields;

ALTER TABLE funding_operations
    ADD CONSTRAINT chk_funding_operations_status_valid
        CHECK (status IN ('CREATED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'RECONCILIATION_REQUIRED')),
    ADD CONSTRAINT chk_funding_operations_status_fields
        CHECK (
            (status = 'CREATED' AND provider_operation_id IS NULL AND journal_transaction_id IS NULL AND completed_at IS NULL AND provider_poll_attempts = 0 AND next_provider_poll_at IS NULL AND unknown_since IS NULL) OR
            (status = 'PROCESSING' AND journal_transaction_id IS NULL AND completed_at IS NULL AND next_provider_poll_at IS NOT NULL) OR
            (status = 'UNKNOWN' AND journal_transaction_id IS NULL AND completed_at IS NULL AND unknown_since IS NOT NULL AND next_provider_poll_at IS NOT NULL) OR
            (status = 'RECONCILIATION_REQUIRED' AND journal_transaction_id IS NULL AND completed_at IS NULL AND next_provider_poll_at IS NULL) OR
            (status = 'SUCCEEDED' AND provider_operation_id IS NOT NULL AND journal_transaction_id IS NOT NULL AND completed_at IS NOT NULL AND next_provider_poll_at IS NULL) OR
            (status = 'FAILED' AND journal_transaction_id IS NULL AND completed_at IS NOT NULL AND next_provider_poll_at IS NULL)
        );

-- 5. Update CHECK constraints on payouts
ALTER TABLE payouts
    DROP CONSTRAINT chk_payouts_status_valid,
    DROP CONSTRAINT chk_payouts_status_fields;

ALTER TABLE payouts
    ADD CONSTRAINT chk_payouts_status_valid
        CHECK (status IN ('CREATED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'RECONCILIATION_REQUIRED')),
    ADD CONSTRAINT chk_payouts_status_fields
        CHECK (
            (status = 'CREATED' AND provider_operation_id IS NULL AND journal_transaction_id IS NULL AND completed_at IS NULL AND provider_poll_attempts = 0 AND next_provider_poll_at IS NULL AND unknown_since IS NULL) OR
            (status = 'PROCESSING' AND journal_transaction_id IS NULL AND completed_at IS NULL AND next_provider_poll_at IS NOT NULL) OR
            (status = 'UNKNOWN' AND journal_transaction_id IS NULL AND completed_at IS NULL AND unknown_since IS NOT NULL AND next_provider_poll_at IS NOT NULL) OR
            (status = 'RECONCILIATION_REQUIRED' AND journal_transaction_id IS NULL AND completed_at IS NULL AND next_provider_poll_at IS NULL) OR
            (status = 'SUCCEEDED' AND provider_operation_id IS NOT NULL AND journal_transaction_id IS NOT NULL AND completed_at IS NOT NULL AND next_provider_poll_at IS NULL) OR
            (status = 'FAILED' AND journal_transaction_id IS NULL AND completed_at IS NOT NULL AND next_provider_poll_at IS NULL)
        );

-- 6. Create targeted partial indexes for status poller due query
CREATE INDEX idx_funding_operations_status_poll
    ON funding_operations(next_provider_poll_at, id)
    WHERE status IN ('UNKNOWN', 'PROCESSING');

CREATE INDEX idx_payouts_status_poll
    ON payouts(next_provider_poll_at, id)
    WHERE status IN ('UNKNOWN', 'PROCESSING');

-- 7. Replace lifecycle and immutability trigger on funding_operations
CREATE OR REPLACE FUNCTION trg_fn_enforce_funding_operations_lifecycle_and_immutability()
RETURNS TRIGGER AS $$
DECLARE
    v_account_type VARCHAR(32);
    v_account_status VARCHAR(32);
    v_account_currency VARCHAR(3);
    v_account_owner UUID;
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
        IF NEW.status <> 'CREATED' THEN
            RAISE EXCEPTION 'Funding operations must be inserted with status CREATED. Direct insertion of status % is forbidden', NEW.status;
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
        IF OLD.status IN ('SUCCEEDED', 'FAILED') THEN
            RAISE EXCEPTION 'FundingOperation % with terminal status % is immutable and cannot be updated', OLD.id, OLD.status;
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

        -- One-way provider_operation_id binding
        IF OLD.provider_operation_id IS NOT NULL AND NEW.provider_operation_id <> OLD.provider_operation_id THEN
            RAISE EXCEPTION 'Cannot modify provider_operation_id from % to % on FundingOperation %', OLD.provider_operation_id, NEW.provider_operation_id, OLD.id;
        END IF;

        IF OLD.provider_operation_id IS NOT NULL AND NEW.provider_operation_id IS NULL THEN
            RAISE EXCEPTION 'Cannot clear provider_operation_id on FundingOperation %', OLD.id;
        END IF;

        -- Legal transitions
        IF OLD.status = 'CREATED' THEN
            IF NEW.status NOT IN ('CREATED', 'PROCESSING', 'FAILED') THEN
                RAISE EXCEPTION 'Invalid status transition for FundingOperation %: % to %', OLD.id, OLD.status, NEW.status;
            END IF;
        ELSIF OLD.status = 'PROCESSING' THEN
            IF NEW.status NOT IN ('PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'RECONCILIATION_REQUIRED') THEN
                RAISE EXCEPTION 'Invalid status transition for FundingOperation %: % to %', OLD.id, OLD.status, NEW.status;
            END IF;
        ELSIF OLD.status = 'UNKNOWN' THEN
            IF NEW.status NOT IN ('UNKNOWN', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'RECONCILIATION_REQUIRED') THEN
                RAISE EXCEPTION 'Invalid status transition for FundingOperation %: % to %', OLD.id, OLD.status, NEW.status;
            END IF;
        ELSIF OLD.status = 'RECONCILIATION_REQUIRED' THEN
            IF NEW.status NOT IN ('RECONCILIATION_REQUIRED', 'SUCCEEDED', 'FAILED') THEN
                RAISE EXCEPTION 'Invalid status transition for FundingOperation %: % to %', OLD.id, OLD.status, NEW.status;
            END IF;
        ELSE
            RAISE EXCEPTION 'Invalid status transition for FundingOperation %: % to %', OLD.id, OLD.status, NEW.status;
        END IF;

        -- Next poll check on nonterminal transitions
        IF NEW.status = 'PROCESSING' AND OLD.status = 'CREATED' AND NEW.next_provider_poll_at IS NULL THEN
            RAISE EXCEPTION 'FundingOperation % transitioning to PROCESSING must have next_provider_poll_at populated', NEW.id;
        END IF;

        IF NEW.status = 'UNKNOWN' AND NEW.next_provider_poll_at IS NULL THEN
            RAISE EXCEPTION 'FundingOperation % transitioning to UNKNOWN must have next_provider_poll_at populated', NEW.id;
        END IF;

        -- Transition-origin provider ID check on FAILED
        IF NEW.status = 'FAILED' THEN
            IF OLD.status = 'CREATED' AND NEW.provider_operation_id IS NOT NULL THEN
                RAISE EXCEPTION 'FundingOperation % failing from CREATED must have provider_operation_id NULL', NEW.id;
            END IF;

            IF OLD.status IN ('UNKNOWN', 'RECONCILIATION_REQUIRED') AND NEW.provider_operation_id IS NULL THEN
                RAISE EXCEPTION 'FundingOperation % failing from provider-attempted state % must have provider_operation_id populated', NEW.id, OLD.status;
            END IF;

            IF NEW.completed_at IS NULL THEN
                RAISE EXCEPTION 'FundingOperation % transitioning to FAILED must have completed_at populated', NEW.id;
            END IF;

            IF NEW.journal_transaction_id IS NOT NULL THEN
                RAISE EXCEPTION 'FundingOperation % transitioning to FAILED must have journal_transaction_id NULL', NEW.id;
            END IF;
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

            SELECT status INTO v_journal_status
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

-- 8. Replace lifecycle and immutability trigger on payouts
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
        IF NEW.status <> 'CREATED' THEN
            RAISE EXCEPTION 'Payouts must be inserted with status CREATED. Direct insertion of status % is forbidden', NEW.status;
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

        -- One-way provider_operation_id binding
        IF OLD.provider_operation_id IS NOT NULL AND NEW.provider_operation_id <> OLD.provider_operation_id THEN
            RAISE EXCEPTION 'Cannot modify provider_operation_id from % to % on Payout %', OLD.provider_operation_id, NEW.provider_operation_id, OLD.id;
        END IF;

        IF OLD.provider_operation_id IS NOT NULL AND NEW.provider_operation_id IS NULL THEN
            RAISE EXCEPTION 'Cannot clear provider_operation_id on Payout %', OLD.id;
        END IF;

        -- Legal transitions
        IF OLD.status = 'CREATED' THEN
            IF NEW.status NOT IN ('CREATED', 'PROCESSING', 'FAILED') THEN
                RAISE EXCEPTION 'Invalid status transition for Payout %: % to %', OLD.id, OLD.status, NEW.status;
            END IF;
        ELSIF OLD.status = 'PROCESSING' THEN
            IF NEW.status NOT IN ('PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'RECONCILIATION_REQUIRED') THEN
                RAISE EXCEPTION 'Invalid status transition for Payout %: % to %', OLD.id, OLD.status, NEW.status;
            END IF;
        ELSIF OLD.status = 'UNKNOWN' THEN
            IF NEW.status NOT IN ('UNKNOWN', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'RECONCILIATION_REQUIRED') THEN
                RAISE EXCEPTION 'Invalid status transition for Payout %: % to %', OLD.id, OLD.status, NEW.status;
            END IF;
        ELSIF OLD.status = 'RECONCILIATION_REQUIRED' THEN
            IF NEW.status NOT IN ('RECONCILIATION_REQUIRED', 'SUCCEEDED', 'FAILED') THEN
                RAISE EXCEPTION 'Invalid status transition for Payout %: % to %', OLD.id, OLD.status, NEW.status;
            END IF;
        ELSE
            RAISE EXCEPTION 'Invalid status transition for Payout %: % to %', OLD.id, OLD.status, NEW.status;
        END IF;

        -- Next poll check on nonterminal transitions
        IF NEW.status = 'PROCESSING' AND OLD.status = 'CREATED' AND NEW.next_provider_poll_at IS NULL THEN
            RAISE EXCEPTION 'Payout % transitioning to PROCESSING must have next_provider_poll_at populated', NEW.id;
        END IF;

        IF NEW.status = 'UNKNOWN' AND NEW.next_provider_poll_at IS NULL THEN
            RAISE EXCEPTION 'Payout % transitioning to UNKNOWN must have next_provider_poll_at populated', NEW.id;
        END IF;

        -- Hold checks for active nonterminal states
        IF NEW.status IN ('PROCESSING', 'UNKNOWN', 'RECONCILIATION_REQUIRED') THEN
            SELECT status INTO v_hold_status
            FROM balance_holds
            WHERE id = NEW.balance_hold_id;

            IF v_hold_status <> 'ACTIVE' THEN
                RAISE EXCEPTION 'Linked balance hold % must be ACTIVE for payout status %, but was %', NEW.balance_hold_id, NEW.status, v_hold_status;
            END IF;
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
            IF OLD.status = 'CREATED' THEN
                IF NEW.provider_operation_id IS NOT NULL THEN
                    RAISE EXCEPTION 'Payout % failing from CREATED must have provider_operation_id NULL', NEW.id;
                END IF;

                SELECT status INTO v_hold_status
                FROM balance_holds
                WHERE id = NEW.balance_hold_id;

                IF v_hold_status NOT IN ('RELEASED', 'EXPIRED') THEN
                    RAISE EXCEPTION 'Linked balance hold % for payout failing from CREATED must be RELEASED or EXPIRED, but was %', NEW.balance_hold_id, v_hold_status;
                END IF;
            ELSIF OLD.status IN ('UNKNOWN', 'RECONCILIATION_REQUIRED') THEN
                IF NEW.provider_operation_id IS NULL THEN
                    RAISE EXCEPTION 'Payout % failing from provider-attempted state % must have provider_operation_id populated', NEW.id, OLD.status;
                END IF;

                SELECT status INTO v_hold_status
                FROM balance_holds
                WHERE id = NEW.balance_hold_id;

                IF v_hold_status <> 'RELEASED' THEN
                    RAISE EXCEPTION 'Linked balance hold % must be RELEASED on provider-attempted payout FAILED transition, but was %', NEW.balance_hold_id, v_hold_status;
                END IF;
            ELSIF OLD.status = 'PROCESSING' THEN
                SELECT status INTO v_hold_status
                FROM balance_holds
                WHERE id = NEW.balance_hold_id;

                IF v_hold_status <> 'RELEASED' THEN
                    RAISE EXCEPTION 'Linked balance hold % must be RELEASED on payout FAILED transition, but was %', NEW.balance_hold_id, v_hold_status;
                END IF;
            END IF;

            IF NEW.completed_at IS NULL THEN
                RAISE EXCEPTION 'Payout % transitioning to FAILED must have completed_at populated', NEW.id;
            END IF;

            IF NEW.journal_transaction_id IS NOT NULL THEN
                RAISE EXCEPTION 'Payout % transitioning to FAILED must have journal_transaction_id NULL', NEW.id;
            END IF;
        END IF;

        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Payouts are immutable and cannot be deleted';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

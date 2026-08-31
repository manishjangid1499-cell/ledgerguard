-- ==============================================================================
-- LedgerGuard Flyway Migration V2: Ledger Foundation Schema & Invariants
-- ==============================================================================

-- 1. Ledger Accounts Table
CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,
    owner_user_id UUID,
    account_type VARCHAR(32) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_ledger_accounts_owner_user_id FOREIGN KEY (owner_user_id) REFERENCES users(id),
    CONSTRAINT chk_ledger_accounts_account_type CHECK (account_type IN ('CUSTOMER', 'MERCHANT', 'PSP_CLEARING', 'PLATFORM_RESERVE', 'PLATFORM_FEES')),
    CONSTRAINT chk_ledger_accounts_status CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT chk_ledger_accounts_currency CHECK (currency = 'INR'),
    CONSTRAINT chk_ledger_accounts_ownership CHECK (
        (account_type IN ('CUSTOMER', 'MERCHANT') AND owner_user_id IS NOT NULL) OR
        (account_type IN ('PSP_CLEARING', 'PLATFORM_RESERVE', 'PLATFORM_FEES') AND owner_user_id IS NULL)
    )
);

CREATE INDEX idx_ledger_accounts_owner_user_id ON ledger_accounts(owner_user_id);

-- 2. Journal Transactions Table
CREATE TABLE journal_transactions (
    id UUID PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    posted_at TIMESTAMPTZ,
    CONSTRAINT chk_journal_transactions_status CHECK (status IN ('DRAFT', 'POSTED')),
    CONSTRAINT chk_journal_transactions_currency CHECK (currency = 'INR'),
    CONSTRAINT chk_journal_transactions_posted_at CHECK (
        (status = 'DRAFT' AND posted_at IS NULL) OR
        (status = 'POSTED' AND posted_at IS NOT NULL)
    )
);

-- 3. Journal Entries Table
CREATE TABLE journal_entries (
    id UUID PRIMARY KEY,
    journal_transaction_id UUID NOT NULL,
    ledger_account_id UUID NOT NULL,
    direction VARCHAR(16) NOT NULL,
    amount_minor BIGINT NOT NULL,
    CONSTRAINT fk_journal_entries_journal_transaction_id FOREIGN KEY (journal_transaction_id) REFERENCES journal_transactions(id),
    CONSTRAINT fk_journal_entries_ledger_account_id FOREIGN KEY (ledger_account_id) REFERENCES ledger_accounts(id),
    CONSTRAINT chk_journal_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_journal_entries_amount_minor CHECK (amount_minor > 0)
);

CREATE INDEX idx_journal_entries_journal_transaction_id ON journal_entries(journal_transaction_id);
CREATE INDEX idx_journal_entries_ledger_account_id ON journal_entries(ledger_account_id);

-- ==============================================================================
-- 4. PostgreSQL Invariant Enforcement Functions & Triggers
-- ==============================================================================

-- Function 4.1: Enforce double-entry balance and structure upon posting
CREATE OR REPLACE FUNCTION trg_fn_enforce_journal_transaction_balance()
RETURNS TRIGGER AS $$
DECLARE
    v_debit_sum BIGINT;
    v_credit_sum BIGINT;
    v_debit_count INT;
    v_credit_count INT;
    v_total_count INT;
BEGIN
    IF NEW.status = 'POSTED' THEN
        SELECT
            COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'DEBIT'), 0),
            COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'CREDIT'), 0),
            COUNT(*) FILTER (WHERE direction = 'DEBIT'),
            COUNT(*) FILTER (WHERE direction = 'CREDIT'),
            COUNT(*)
        INTO
            v_debit_sum,
            v_credit_sum,
            v_debit_count,
            v_credit_count,
            v_total_count
        FROM journal_entries
        WHERE journal_transaction_id = NEW.id;

        IF v_total_count < 2 THEN
            RAISE EXCEPTION 'Journal transaction % must contain at least 2 entries to be posted (found: %)', NEW.id, v_total_count;
        END IF;

        IF v_debit_count < 1 OR v_credit_count < 1 THEN
            RAISE EXCEPTION 'Journal transaction % must contain at least one debit and one credit entry (debits: %, credits: %)', NEW.id, v_debit_count, v_credit_count;
        END IF;

        IF v_debit_sum <> v_credit_sum THEN
            RAISE EXCEPTION 'Journal transaction % is not balanced: debit sum % != credit sum %', NEW.id, v_debit_sum, v_credit_sum;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_transactions_balance_check
BEFORE UPDATE OF status ON journal_transactions
FOR EACH ROW
EXECUTE FUNCTION trg_fn_enforce_journal_transaction_balance();

-- Function 4.2: Enforce journal transaction lifecycle and immutability
CREATE OR REPLACE FUNCTION trg_fn_enforce_journal_transaction_immutability()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Journal transactions must be inserted with status DRAFT. Direct insertion of status % is forbidden', NEW.status;
        END IF;
        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.status = 'POSTED' THEN
            RAISE EXCEPTION 'Posted journal transaction % is immutable and cannot be updated', OLD.id;
        END IF;
        IF OLD.status = 'DRAFT' AND NEW.status <> 'POSTED' THEN
            RAISE EXCEPTION 'Invalid status transition for journal transaction %: % to %', OLD.id, OLD.status, NEW.status;
        END IF;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        IF OLD.status = 'POSTED' THEN
            RAISE EXCEPTION 'Posted journal transaction % is immutable and cannot be deleted', OLD.id;
        END IF;
        RETURN OLD;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_transactions_immutability
BEFORE INSERT OR UPDATE OR DELETE ON journal_transactions
FOR EACH ROW
EXECUTE FUNCTION trg_fn_enforce_journal_transaction_immutability();

-- Function 4.3: Enforce posted journal entry immutability and serialize concurrent appends
CREATE OR REPLACE FUNCTION trg_fn_enforce_journal_entry_immutability()
RETURNS TRIGGER AS $$
DECLARE
    v_parent_status VARCHAR(32);
    v_target_txn_id UUID;
BEGIN
    IF TG_OP = 'INSERT' THEN
        v_target_txn_id := NEW.journal_transaction_id;
        -- Acquire shared row lock on parent journal transaction to serialize with concurrent posting transitions
        SELECT status INTO v_parent_status
        FROM journal_transactions
        WHERE id = v_target_txn_id
        FOR SHARE;
    ELSE
        v_target_txn_id := OLD.journal_transaction_id;
        SELECT status INTO v_parent_status
        FROM journal_transactions
        WHERE id = v_target_txn_id;
    END IF;

    IF v_parent_status IS NULL THEN
        -- Nonexistent parent handled by foreign key constraint
        RETURN COALESCE(NEW, OLD);
    END IF;

    IF v_parent_status = 'POSTED' THEN
        IF TG_OP = 'INSERT' THEN
            RAISE EXCEPTION 'Cannot insert entries into posted journal transaction %', v_target_txn_id;
        ELSIF TG_OP = 'UPDATE' THEN
            RAISE EXCEPTION 'Cannot update entries of posted journal transaction %', v_target_txn_id;
        ELSIF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'Cannot delete entries of posted journal transaction %', v_target_txn_id;
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    ELSE
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entries_immutability
BEFORE INSERT OR UPDATE OR DELETE ON journal_entries
FOR EACH ROW
EXECUTE FUNCTION trg_fn_enforce_journal_entry_immutability();

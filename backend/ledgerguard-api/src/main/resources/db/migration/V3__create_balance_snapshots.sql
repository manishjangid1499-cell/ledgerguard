-- Flyway V3: Create balance snapshots table, partial unique index, and automatic snapshot triggers

-- 1. Replace non-unique owner index with unique index enforcing at most one owned ledger account per user
DROP INDEX IF EXISTS idx_ledger_accounts_owner_user_id;
CREATE UNIQUE INDEX idx_ledger_accounts_owner_user_id
    ON ledger_accounts(owner_user_id)
    WHERE owner_user_id IS NOT NULL;

-- 2. Derived balance snapshots table
CREATE TABLE ledger_balance_snapshots (
    ledger_account_id UUID PRIMARY KEY,
    balance_minor BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_balance_snapshots_ledger_account_id
        FOREIGN KEY (ledger_account_id)
        REFERENCES ledger_accounts(id)
        ON DELETE RESTRICT
);

-- 3. Trigger to auto-initialize zero snapshot on newly created ledger accounts
CREATE OR REPLACE FUNCTION trg_fn_init_ledger_account_snapshot()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO ledger_balance_snapshots (ledger_account_id, balance_minor, updated_at)
    VALUES (NEW.id, 0, NEW.created_at);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_accounts_init_snapshot
    AFTER INSERT ON ledger_accounts
    FOR EACH ROW
    EXECUTE FUNCTION trg_fn_init_ledger_account_snapshot();

-- 4. Backfill existing ledger accounts from authoritative POSTED journal history
INSERT INTO ledger_balance_snapshots (ledger_account_id, balance_minor, updated_at)
SELECT
    la.id AS ledger_account_id,
    COALESCE(
        SUM(
            CASE
                WHEN jt.status = 'POSTED' THEN
                    CASE
                        WHEN la.account_type IN ('CUSTOMER', 'MERCHANT', 'PLATFORM_FEES') THEN
                            CASE WHEN je.direction = 'CREDIT' THEN je.amount_minor ELSE -je.amount_minor END
                        ELSE -- DEBIT-normal: PSP_CLEARING, PLATFORM_RESERVE
                            CASE WHEN je.direction = 'DEBIT' THEN je.amount_minor ELSE -je.amount_minor END
                    END
                ELSE 0
            END
        ),
        0
    )::BIGINT AS balance_minor,
    COALESCE(MAX(CASE WHEN jt.status = 'POSTED' THEN jt.posted_at ELSE NULL END), la.created_at) AS updated_at
FROM ledger_accounts la
LEFT JOIN journal_entries je ON je.ledger_account_id = la.id
LEFT JOIN journal_transactions jt ON jt.id = je.journal_transaction_id
GROUP BY la.id, la.account_type, la.created_at;

-- 5. Trigger to atomically update balance snapshots upon journal posting
CREATE OR REPLACE FUNCTION trg_fn_update_balance_snapshots_on_posting()
RETURNS TRIGGER AS $$
DECLARE
    rec RECORD;
    current_bal BIGINT;
    new_bal NUMERIC;
BEGIN
    IF OLD.status = 'DRAFT' AND NEW.status = 'POSTED' THEN
        -- Aggregate deltas per ledger account in deterministic order (ORDER BY ledger_account_id ASC)
        FOR rec IN
            SELECT
                je.ledger_account_id,
                SUM(
                    CASE
                        WHEN la.account_type IN ('CUSTOMER', 'MERCHANT', 'PLATFORM_FEES') THEN
                            CASE WHEN je.direction = 'CREDIT' THEN je.amount_minor ELSE -je.amount_minor END
                        ELSE -- DEBIT-normal: PSP_CLEARING, PLATFORM_RESERVE
                            CASE WHEN je.direction = 'DEBIT' THEN je.amount_minor ELSE -je.amount_minor END
                    END
                ) AS delta_minor
            FROM journal_entries je
            JOIN ledger_accounts la ON la.id = je.ledger_account_id
            WHERE je.journal_transaction_id = NEW.id
            GROUP BY je.ledger_account_id, la.account_type
            ORDER BY je.ledger_account_id ASC
        LOOP
            -- Lock and read the current balance snapshot
            SELECT balance_minor INTO current_bal
            FROM ledger_balance_snapshots
            WHERE ledger_account_id = rec.ledger_account_id
            FOR UPDATE;

            IF current_bal IS NULL THEN
                RAISE EXCEPTION 'Balance snapshot missing for ledger account %', rec.ledger_account_id;
            END IF;

            new_bal := (current_bal::NUMERIC) + (rec.delta_minor::NUMERIC);

            IF new_bal > 9223372036854775807 OR new_bal < -9223372036854775808 THEN
                RAISE EXCEPTION 'Balance snapshot arithmetic overflow for ledger account %: new balance % exceeds signed 64-bit integer range',
                    rec.ledger_account_id, new_bal;
            END IF;

            UPDATE ledger_balance_snapshots
            SET balance_minor = new_bal::BIGINT,
                updated_at = NEW.posted_at
            WHERE ledger_account_id = rec.ledger_account_id;
        END LOOP;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_transactions_update_snapshots
    AFTER UPDATE OF status ON journal_transactions
    FOR EACH ROW
    EXECUTE FUNCTION trg_fn_update_balance_snapshots_on_posting();

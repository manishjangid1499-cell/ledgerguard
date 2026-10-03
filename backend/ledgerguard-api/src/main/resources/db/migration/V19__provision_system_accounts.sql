-- ==============================================================================
-- LedgerGuard Flyway Migration V19: System Account Provisioning & Invariants
-- ==============================================================================
-- Ensures canonical system accounts exist for currency INR:
--   1. PSP_CLEARING     (Asset, DEBIT-normal)
--   2. PLATFORM_FEES    (Revenue/Liability, CREDIT-normal)
--   3. PLATFORM_RESERVE (Asset/Equity, DEBIT-normal)
--
-- Rules:
--   - Each required system account must have owner_user_id NULL, status ACTIVE, currency INR.
--   - Concurrency & Locking: Acquires an exclusive table lock on ledger_accounts with a bounded
--     lock timeout (5 seconds) within the Flyway migration transaction before validation or insertion.
--   - Missing accounts are created; the existing AFTER INSERT trigger (trg_ledger_accounts_init_snapshot)
--     initializes their zero snapshots in ledger_balance_snapshots.
--   - Existing valid accounts, their IDs, snapshots, and journal histories are preserved.
--   - If duplicate or invalid (e.g. non-ACTIVE, wrong currency, or owner_user_id NOT NULL) accounts
--     exist for any system account type, the migration fails transactionally with RAISE EXCEPTION.
--   - This migration validates singleton system accounts while holding a table lock.
--     It does not add a database uniqueness constraint or prevent duplicate accounts
--     from being inserted after migration. Existing service-level checks continue
--     to fail closed when required accounts are missing or ambiguous.
-- ==============================================================================

-- Bound lock acquisition wait time to prevent indefinite blocking during migration
SET LOCAL lock_timeout = '5s';

-- Lock ledger_accounts exclusively for the remainder of this migration transaction
-- to serialize against concurrent account insertions or status updates.
LOCK TABLE ledger_accounts IN EXCLUSIVE MODE;

DO $$
DECLARE
    v_types TEXT[] := ARRAY['PSP_CLEARING', 'PLATFORM_FEES', 'PLATFORM_RESERVE'];
    v_type TEXT;
    v_total_count INT;
    v_active_valid_count INT;
    v_new_id UUID;
BEGIN
    FOREACH v_type IN ARRAY v_types
    LOOP
        -- Count total accounts for this system account type
        SELECT COUNT(*)
        INTO v_total_count
        FROM ledger_accounts
        WHERE account_type = v_type;

        -- Count accounts that are fully valid: ACTIVE, INR, owner_user_id IS NULL
        SELECT COUNT(*)
        INTO v_active_valid_count
        FROM ledger_accounts
        WHERE account_type = v_type
          AND status = 'ACTIVE'
          AND currency = 'INR'
          AND owner_user_id IS NULL;

        IF v_total_count > 1 THEN
            RAISE EXCEPTION 'Cannot provision system accounts: duplicate accounts found for type % (found % accounts)',
                v_type, v_total_count;
        ELSIF v_total_count = 1 AND v_active_valid_count = 0 THEN
            RAISE EXCEPTION 'Cannot provision system accounts: invalid existing account found for type % (expected status ACTIVE, currency INR, owner_user_id NULL)',
                v_type;
        ELSIF v_total_count = 1 AND v_active_valid_count = 1 THEN
            -- Exactly one valid active account exists. Preserve it completely.
            NULL;
        ELSE
            -- v_total_count = 0: Missing account. Provision it.
            -- The AFTER INSERT trigger trg_ledger_accounts_init_snapshot automatically
            -- initializes the corresponding zero-balance snapshot in ledger_balance_snapshots.
            v_new_id := gen_random_uuid();
            INSERT INTO ledger_accounts (
                id,
                owner_user_id,
                account_type,
                currency,
                status,
                created_at,
                updated_at
            ) VALUES (
                v_new_id,
                NULL,
                v_type,
                'INR',
                'ACTIVE',
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP
            );
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql;
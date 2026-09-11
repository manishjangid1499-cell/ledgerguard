#!/usr/bin/env bash
# ==============================================================================
# LedgerGuard PostgreSQL Logical Restore & Financial Validation Script
# Phase 40 Deliverable: scripts/restore-db.sh
# ==============================================================================
# Restores a logical backup archive into an isolated fresh PostgreSQL database
# and executes mandatory Mode A financial invariant verification before declaring success.
# ==============================================================================

set -Eeuo pipefail
umask 077

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
TARGET_DB="${TARGET_DB:-ledgerguard_phase40_restore}"
ADMIN_USER="${ADMIN_USER:-postgres}"
APP_USER="${APP_USER:-ledgerguard_app}"
ARCHIVE=""
VALIDATE_ONLY=false
FORCE_DROP=false
CONFIRM_TARGET=""

print_usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  -f, --archive PATH         Path to pg_dump custom format (-Fc) archive file
  -t, --target-db NAME       Target database name (default: ${TARGET_DB})
  -c, --compose-file PATH    Path to docker-compose file (default: ${COMPOSE_FILE})
  -a, --admin-user USER      PostgreSQL administrative role (default: ${ADMIN_USER})
  -u, --app-user USER        PostgreSQL application role (default: ${APP_USER})
  --validate-only            Execute Mode A financial validation only without restoring
  --force-destructive-drop   Allow dropping target database if it already exists
  --confirm-target-db NAME   Must match target-db name when --force-destructive-drop is used
  -h, --help                 Show this help message and exit

Examples:
  $(basename "$0") -f ./tmp/backups/ledgerguard_20260911_120000Z.dump -t ledgerguard_drill
  $(basename "$0") --validate-only -t ledgerguard_drill
EOF
}

# Parse options
while [[ $# -gt 0 ]]; do
    case "$1" in
        -f|--archive)
            ARCHIVE="$2"
            shift 2
            ;;
        -t|--target-db)
            TARGET_DB="$2"
            shift 2
            ;;
        -c|--compose-file)
            COMPOSE_FILE="$2"
            shift 2
            ;;
        -a|--admin-user)
            ADMIN_USER="$2"
            shift 2
            ;;
        -u|--app-user)
            APP_USER="$2"
            shift 2
            ;;
        --validate-only)
            VALIDATE_ONLY=true
            shift
            ;;
        --force-destructive-drop)
            FORCE_DROP=true
            shift
            ;;
        --confirm-target-db)
            CONFIRM_TARGET="$2"
            shift 2
            ;;
        -h|--help)
            print_usage
            exit 0
            ;;
        *)
            echo "ERROR: Unknown option '$1'" >&2
            print_usage >&2
            exit 1
            ;;
    esac
done

# Validate target database and role names to prevent command injection
if [[ ! "$TARGET_DB" =~ ^[a-zA-Z0-9_]+$ ]]; then
    echo "ERROR: Invalid target database name '${TARGET_DB}'. Must contain only alphanumeric characters and underscores." >&2
    exit 1
fi

if [[ ! "$ADMIN_USER" =~ ^[a-zA-Z0-9_]+$ ]]; then
    echo "ERROR: Invalid admin user role '${ADMIN_USER}'. Must contain only alphanumeric characters and underscores." >&2
    exit 1
fi

if [[ ! "$APP_USER" =~ ^[a-zA-Z0-9_]+$ ]]; then
    echo "ERROR: Invalid app user role '${APP_USER}'. Must contain only alphanumeric characters and underscores." >&2
    exit 1
fi

# Hard safety rule: Never restore directly over authoritative source 'ledgerguard'
if [[ "$TARGET_DB" == "ledgerguard" ]]; then
    echo "CRITICAL SAFETY ERROR: Direct restore over authoritative source database 'ledgerguard' is strictly prohibited by operational policy." >&2
    exit 1
fi

echo "=== LedgerGuard Operational Database Restore ==="
echo "Target Database:  ${TARGET_DB}"
echo "Admin User:       ${ADMIN_USER}"
echo "App User:         ${APP_USER}"
echo "Compose File:     ${COMPOSE_FILE}"
echo "Validate Only:    ${VALIDATE_ONLY}"

# Verify container connectivity
if ! docker compose -f "$COMPOSE_FILE" exec -T postgres pg_isready -U "$ADMIN_USER" -d postgres >/dev/null 2>&1; then
    echo "ERROR: PostgreSQL service in ${COMPOSE_FILE} is unreachable as user '${ADMIN_USER}'." >&2
    exit 1
fi

if [[ "$VALIDATE_ONLY" == false ]]; then
    if [[ -z "$ARCHIVE" ]]; then
        echo "ERROR: Backup archive file must be specified with -f or --archive (unless --validate-only is set)." >&2
        print_usage >&2
        exit 1
    fi

    if [[ ! -f "$ARCHIVE" || ! -s "$ARCHIVE" ]]; then
        echo "ERROR: Archive file '${ARCHIVE}' does not exist or is empty." >&2
        exit 1
    fi

    echo "Archive:          ${ARCHIVE}"

    # 1. Fail-closed Checksum Verification: Sidecar is MANDATORY and must match
    CHECKSUM_FILE="${ARCHIVE}.sha256"
    if [[ ! -f "$CHECKSUM_FILE" || ! -s "$CHECKSUM_FILE" ]]; then
        echo "CRITICAL ERROR: Mandatory SHA-256 sidecar '${CHECKSUM_FILE}' is missing or empty. Refusing restore." >&2
        exit 1
    fi

    echo "Verifying mandatory SHA-256 checksum from sidecar..."
    EXPECTED_HASH=$(awk '{print $1}' "$CHECKSUM_FILE" | tr -d '\r\n')

    # Validate that expected hash is exactly 64 hexadecimal characters
    if [[ ! "$EXPECTED_HASH" =~ ^[0-9a-fA-F]{64}$ ]]; then
        echo "CRITICAL ERROR: Checksum sidecar '${CHECKSUM_FILE}' does not contain a valid 64-character hexadecimal SHA-256 hash." >&2
        exit 1
    fi

    # Calculate actual SHA-256 directly over "$ARCHIVE"
    if command -v sha256sum >/dev/null 2>&1; then
        ACTUAL_HASH=$(sha256sum "$ARCHIVE" | awk '{print $1}' | tr -d '\r\n')
    elif command -v shasum >/dev/null 2>&1; then
        ACTUAL_HASH=$(shasum -a 256 "$ARCHIVE" | awk '{print $1}' | tr -d '\r\n')
    else
        echo "CRITICAL ERROR: Neither sha256sum nor shasum is available on host to verify sidecar checksum." >&2
        exit 1
    fi

    # Convert to lowercase for comparison
    EXPECTED_HASH_LOWER=$(echo "$EXPECTED_HASH" | tr '[:upper:]' '[:lower:]')
    ACTUAL_HASH_LOWER=$(echo "$ACTUAL_HASH" | tr '[:upper:]' '[:lower:]')

    if [[ "$EXPECTED_HASH_LOWER" != "$ACTUAL_HASH_LOWER" ]]; then
        echo "CRITICAL ERROR: SHA-256 checksum mismatch for '${ARCHIVE}'." >&2
        echo "  Expected: ${EXPECTED_HASH_LOWER}" >&2
        echo "  Actual:   ${ACTUAL_HASH_LOWER}" >&2
        exit 1
    fi
    echo "Checksum:         VERIFIED"

    # 2. Verify archive table of contents with pg_restore --list before touching database
    echo "Inspecting archive table of contents (pg_restore --list)..."
    if ! docker compose -f "$COMPOSE_FILE" exec -T postgres pg_restore --list < "$ARCHIVE" >/dev/null 2>&1; then
        echo "CRITICAL ERROR: Archive file '${ARCHIVE}' is invalid or corrupted (pg_restore --list failed)." >&2
        exit 1
    fi
    echo "Archive TOC:      VALID"

    # 3. Target database safety checks
    DB_EXISTS=$(docker compose -f "$COMPOSE_FILE" exec -T postgres psql -U "$ADMIN_USER" -d postgres -tAc \
        "SELECT 1 FROM pg_database WHERE datname = '${TARGET_DB}';" | tr -d '\r')

    if [[ "$DB_EXISTS" == "1" ]]; then
        if [[ "$FORCE_DROP" == true && "$CONFIRM_TARGET" == "$TARGET_DB" ]]; then
            echo "Target database '${TARGET_DB}' exists and --force-destructive-drop confirmed. Dropping..."
            docker compose -f "$COMPOSE_FILE" exec -T postgres psql -U "$ADMIN_USER" -d postgres -v ON_ERROR_STOP=1 -c \
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${TARGET_DB}' AND pid <> pg_backend_pid();" >/dev/null
            docker compose -f "$COMPOSE_FILE" exec -T postgres psql -U "$ADMIN_USER" -d postgres -v ON_ERROR_STOP=1 -c \
                "DROP DATABASE \"${TARGET_DB}\";" >/dev/null
        else
            echo "ERROR: Target database '${TARGET_DB}' already exists. Refusing to overwrite by default." >&2
            echo "To replace deliberately, specify: --force-destructive-drop --confirm-target-db '${TARGET_DB}'" >&2
            exit 1
        fi
    fi

    # 4. Create fresh target database owned by ledgerguard_app with strict connection isolation
    echo "Creating fresh target database '${TARGET_DB}' owned by '${APP_USER}'..."
    docker compose -f "$COMPOSE_FILE" exec -T postgres psql -U "$ADMIN_USER" -d postgres -v ON_ERROR_STOP=1 <<EOSQL >/dev/null
        CREATE DATABASE "${TARGET_DB}" OWNER "${APP_USER}";
        REVOKE CONNECT ON DATABASE "${TARGET_DB}" FROM PUBLIC;
        GRANT CONNECT ON DATABASE "${TARGET_DB}" TO "${APP_USER}";
EOSQL

    # 5. Restore into target database directly as application role
    echo "Restoring database archive as '${APP_USER}' into '${TARGET_DB}'..."
    docker compose -f "$COMPOSE_FILE" exec -T postgres \
        pg_restore -U "$APP_USER" -d "$TARGET_DB" --no-owner --no-privileges --single-transaction --exit-on-error < "$ARCHIVE"

    # 6. Post-restore ownership and privilege verifications
    echo "Verifying database and object ownership..."
    RESTORED_DB_OWNER=$(docker compose -f "$COMPOSE_FILE" exec -T postgres psql -U "$ADMIN_USER" -d postgres -tAc \
        "SELECT d.datdba::regrole::text FROM pg_database d WHERE d.datname = '${TARGET_DB}';" | tr -d '\r')
    if [[ "$RESTORED_DB_OWNER" != "$APP_USER" ]]; then
        echo "CRITICAL ERROR: Target database owner is '${RESTORED_DB_OWNER}', expected '${APP_USER}'." >&2
        exit 1
    fi

    # Broaden ownership verification to tables, views, sequences, and functions/procedures in public schema
    NON_APP_OBJECTS=$(docker compose -f "$COMPOSE_FILE" exec -T postgres psql -U "$ADMIN_USER" -d "$TARGET_DB" -tAc \
        "SELECT count(*) FROM (
            SELECT tablename AS name FROM pg_tables WHERE schemaname = 'public' AND tableowner != '${APP_USER}'
            UNION ALL
            SELECT viewname AS name FROM pg_views WHERE schemaname = 'public' AND viewowner != '${APP_USER}'
            UNION ALL
            SELECT sequencename AS name FROM pg_sequences WHERE schemaname = 'public' AND sequenceowner != '${APP_USER}'
            UNION ALL
            SELECT p.proname AS name FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND pg_get_userbyid(p.proowner) != '${APP_USER}'
        ) non_owned;" | tr -d '\r')
    if [[ "$NON_APP_OBJECTS" != "0" ]]; then
        echo "CRITICAL ERROR: Found ${NON_APP_OBJECTS} public objects (tables/views/sequences/routines) not owned by '${APP_USER}'." >&2
        exit 1
    fi

    # Ensure app user does not have administrative cluster privileges
    APP_CLUSTER_PRIVS=$(docker compose -f "$COMPOSE_FILE" exec -T postgres psql -U "$ADMIN_USER" -d postgres -tAc \
        "SELECT rolsuper::text || '|' || rolcreaterole::text || '|' || rolcreatedb::text FROM pg_roles WHERE rolname = '${APP_USER}';" | tr -d '\r')
    if [[ "$APP_CLUSTER_PRIVS" != "false|false|false" ]]; then
        echo "CRITICAL ERROR: Role '${APP_USER}' possesses unexpected cluster privileges: ${APP_CLUSTER_PRIVS}." >&2
        exit 1
    fi
    echo "Ownership:        VERIFIED (${APP_USER})"
fi

# ==============================================================================
# Mode A: Integrated Financial Invariant & Schema Self-Consistency Validation
# ==============================================================================
echo "=== Executing Mode A Financial Invariant Validation ==="

run_check() {
    local check_name="$1"
    local sql_query="$2"
    local expected_output="$3"

    local actual_output
    actual_output=$(docker compose -f "$COMPOSE_FILE" exec -T postgres psql -U "$ADMIN_USER" -d "$TARGET_DB" -tAc "$sql_query" 2>&1 | tr -d '\r')

    if [[ "$actual_output" != "$expected_output" ]]; then
        echo "FAILED: ${check_name}" >&2
        echo "  Expected: '${expected_output}'" >&2
        echo "  Actual:   '${actual_output}'" >&2
        return 1
    fi
    echo "  [PASS] ${check_name}"
    return 0
}

# A. Required Schema: Verify all 20 tables exist
SCHEMA_CHECK_SQL="
SELECT count(*) FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN (
    'users', 'refresh_tokens', 'ledger_accounts', 'journal_transactions',
    'journal_entries', 'ledger_balance_snapshots', 'idempotency_records',
    'transfers', 'payments', 'refunds', 'balance_holds', 'outbox_events',
    'funding_operations', 'payouts', 'provider_events', 'reconciliation_runs',
    'reconciliation_items', 'reconciliation_cases', 'audit_events',
    'flyway_schema_history'
  );"
run_check "Schema Completeness (20 Tables)" "$SCHEMA_CHECK_SQL" "20" || exit 1

# B. Flyway History: Exact set {1..17} all success=true, count=17, no versions > 17
FLYWAY_SET_SQL="
WITH expected AS (SELECT generate_series(1, 17)::text AS v),
     actual AS (SELECT version FROM flyway_schema_history WHERE success = true AND version ~ '^[0-9]+$')
SELECT count(*) FROM (
    SELECT v FROM expected EXCEPT SELECT version FROM actual
    UNION ALL
    SELECT version FROM actual WHERE version::int > 17
) diff;"
FLYWAY_COUNT_SQL="SELECT count(*) FROM flyway_schema_history WHERE success = true AND version ~ '^[0-9]+$';"
FLYWAY_MAX_SQL="SELECT max(version::int) FROM flyway_schema_history WHERE type = 'SQL' AND version ~ '^[0-9]+$';"

run_check "Flyway Exact Set {1..17} Success" "$FLYWAY_SET_SQL" "0" || exit 1
run_check "Flyway Total Migration Count = 17" "$FLYWAY_COUNT_SQL" "17" || exit 1
run_check "Flyway Max Numeric Version = 17" "$FLYWAY_MAX_SQL" "17" || exit 1

# C. POSTED Journal Structure: >=2 entries, >=1 debit, >=1 credit, balanced sum
JOURNAL_STRUCTURE_SQL="
SELECT count(*) FROM (
    SELECT jt.id
    FROM journal_transactions jt
    LEFT JOIN journal_entries je ON je.journal_transaction_id = jt.id
    WHERE jt.status = 'POSTED'
    GROUP BY jt.id
    HAVING count(je.id) < 2
        OR count(CASE WHEN je.direction = 'DEBIT' THEN 1 END) < 1
        OR count(CASE WHEN je.direction = 'CREDIT' THEN 1 END) < 1
        OR sum(CASE WHEN je.direction = 'DEBIT' THEN je.amount_minor::numeric ELSE 0 END)
           <> sum(CASE WHEN je.direction = 'CREDIT' THEN je.amount_minor::numeric ELSE 0 END)
) malformed;"
run_check "POSTED Journal Transaction Structure" "$JOURNAL_STRUCTURE_SQL" "0" || exit 1

# D. Global POSTED Zero-Sum: SUM(Debits) == SUM(Credits) across all POSTED transactions
GLOBAL_ZERO_SUM_SQL="
SELECT COALESCE(SUM(CASE WHEN je.direction = 'DEBIT' THEN je.amount_minor::numeric ELSE 0 END), 0) -
       COALESCE(SUM(CASE WHEN je.direction = 'CREDIT' THEN je.amount_minor::numeric ELSE 0 END), 0)
FROM journal_transactions jt
JOIN journal_entries je ON je.journal_transaction_id = jt.id
WHERE jt.status = 'POSTED';"
run_check "Global POSTED Zero-Sum Balance" "$GLOBAL_ZERO_SUM_SQL" "0" || exit 1

# E. Balance Snapshot Parity: Account-type normal balance reconstruction matches ledger_balance_snapshots
SNAPSHOT_PARITY_SQL="
WITH recon AS (
    SELECT la.id AS account_id,
           la.account_type,
           COALESCE(lbs.balance_minor, NULL) AS snapshot_balance,
           COALESCE(
               SUM(
                    CASE
                        WHEN jt.status = 'POSTED' THEN
                            CASE
                                WHEN la.account_type IN ('CUSTOMER', 'MERCHANT', 'PLATFORM_FEES') THEN
                                    CASE WHEN je.direction = 'CREDIT' THEN je.amount_minor::numeric ELSE -je.amount_minor::numeric END
                                ELSE -- Debit-normal: PSP_CLEARING, PLATFORM_RESERVE
                                    CASE WHEN je.direction = 'DEBIT' THEN je.amount_minor::numeric ELSE -je.amount_minor::numeric END
                            END
                        ELSE 0
                    END
               ), 0
           ) AS reconstructed_balance
    FROM ledger_accounts la
    LEFT JOIN ledger_balance_snapshots lbs ON lbs.ledger_account_id = la.id
    LEFT JOIN journal_entries je ON je.ledger_account_id = la.id
    LEFT JOIN journal_transactions jt ON jt.id = je.journal_transaction_id
    GROUP BY la.id, la.account_type, lbs.balance_minor
)
SELECT count(*) FROM recon
WHERE snapshot_balance IS NULL
   OR snapshot_balance::numeric <> reconstructed_balance;"
run_check "Balance Snapshot Parity (Normal Balances)" "$SNAPSHOT_PARITY_SQL" "0" || exit 1

# F. Required Database Triggers: Exactly 21 relation-trigger pairs present and enabled ('O')
TRIGGER_CHECK_SQL="
WITH expected_triggers(relname, tgname) AS (
    VALUES
        ('audit_events', 'trg_audit_events_no_truncate'),
        ('audit_events', 'trg_audit_events_no_update_delete'),
        ('balance_holds', 'trg_balance_holds_integrity'),
        ('funding_operations', 'trg_funding_operations_lifecycle_and_immutability'),
        ('idempotency_records', 'trg_idempotency_records_immutability'),
        ('journal_entries', 'trg_journal_entries_immutability'),
        ('journal_transactions', 'trg_journal_transactions_balance_check'),
        ('journal_transactions', 'trg_journal_transactions_immutability'),
        ('journal_transactions', 'trg_journal_transactions_update_snapshots'),
        ('ledger_accounts', 'trg_ledger_accounts_init_snapshot'),
        ('outbox_events', 'trg_outbox_events_integrity'),
        ('payments', 'trg_payments_lifecycle_and_immutability'),
        ('payouts', 'trg_payouts_lifecycle_and_immutability'),
        ('provider_events', 'trg_provider_events_immutability'),
        ('reconciliation_cases', 'trg_recon_cases_lifecycle'),
        ('reconciliation_items', 'trg_recon_items_auto_create_case'),
        ('reconciliation_items', 'trg_recon_items_immutability'),
        ('reconciliation_runs', 'trg_recon_runs_lifecycle'),
        ('refunds', 'trg_refunds_integrity'),
        ('transfers', 'trg_transfers_immutability'),
        ('transfers', 'trg_transfers_validate_journal_posted')
),
actual_triggers AS (
    SELECT c.relname, t.tgname
    FROM pg_trigger t
    JOIN pg_class c ON c.oid = t.tgrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
      AND t.tgenabled = 'O'
      AND NOT t.tgisinternal
)
SELECT count(*) FROM (
    SELECT relname, tgname FROM expected_triggers EXCEPT SELECT relname, tgname FROM actual_triggers
    UNION ALL
    SELECT relname, tgname FROM actual_triggers EXCEPT SELECT relname, tgname FROM expected_triggers
) diff;"
run_check "Authoritative Triggers (21 Table-Trigger Pairs Enabled)" "$TRIGGER_CHECK_SQL" "0" || exit 1

# G. Outbox Structure & V17 Trace Columns
OUTBOX_STATUS_SQL="SELECT count(*) FROM outbox_events WHERE status NOT IN ('PENDING', 'PUBLISHED');"
OUTBOX_TRACE_COLS_SQL="
SELECT count(*) FROM information_schema.columns
WHERE table_name = 'outbox_events'
  AND column_name IN ('traceparent', 'tracestate', 'correlation_id');"
run_check "Outbox Status Validity (PENDING/PUBLISHED)" "$OUTBOX_STATUS_SQL" "0" || exit 1
run_check "Outbox V17 Trace Context Columns" "$OUTBOX_TRACE_COLS_SQL" "3" || exit 1

echo ""
echo "=== RESTORE AND MODE A VALIDATION PASSED ==="
echo "Target Database '${TARGET_DB}' is structurally sound and mathematically consistent."

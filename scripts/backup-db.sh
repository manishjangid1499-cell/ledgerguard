#!/usr/bin/env bash
# ==============================================================================
# LedgerGuard PostgreSQL Logical Backup Script
# Phase 40 Deliverable: scripts/backup-db.sh
# ==============================================================================
# Takes a safe logical backup of an authoritative LedgerGuard PostgreSQL database
# using pg_dump custom format (-Fc) streamed directly from the PostgreSQL container.
# ==============================================================================

set -Eeuo pipefail
umask 077

# Defaults
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
DB_NAME="${DB_NAME:-ledgerguard}"
DB_USER="${DB_USER:-ledgerguard_app}"
OUTPUT_DIR="${OUTPUT_DIR:-./tmp/backups}"

print_usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  -c, --compose-file PATH   Path to docker-compose file (default: ${COMPOSE_FILE})
  -d, --dbname NAME         Database to back up (default: ${DB_NAME})
  -u, --user USER           Database application user (default: ${DB_USER})
  -o, --output-dir DIR      Destination backup directory (default: ${OUTPUT_DIR})
  -h, --help                Show this help message and exit

Environment Overrides:
  COMPOSE_FILE, DB_NAME, DB_USER, OUTPUT_DIR

Requirements:
  Docker Compose running with target PostgreSQL container service.
EOF
}

# Parse command line options
while [[ $# -gt 0 ]]; do
    case "$1" in
        -c|--compose-file)
            COMPOSE_FILE="$2"
            shift 2
            ;;
        -d|--dbname)
            DB_NAME="$2"
            shift 2
            ;;
        -u|--user)
            DB_USER="$2"
            shift 2
            ;;
        -o|--output-dir)
            OUTPUT_DIR="$2"
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

# Validate database and role identifiers to prevent command injection
if [[ ! "$DB_NAME" =~ ^[a-zA-Z0-9_]+$ ]]; then
    echo "ERROR: Invalid database name '${DB_NAME}'. Must contain only alphanumeric characters and underscores." >&2
    exit 1
fi

if [[ ! "$DB_USER" =~ ^[a-zA-Z0-9_]+$ ]]; then
    echo "ERROR: Invalid database user role '${DB_USER}'. Must contain only alphanumeric characters and underscores." >&2
    exit 1
fi

# Ensure output directory exists
mkdir -p "$OUTPUT_DIR"

TIMESTAMP="$(date -u +'%Y%m%d_%H%M%SZ')"
BACKUP_FILENAME="${DB_NAME}_${TIMESTAMP}.dump"
BACKUP_FILE="${OUTPUT_DIR}/${BACKUP_FILENAME}"
CHECKSUM_FILE="${BACKUP_FILE}.sha256"

# Cleanup tracking on exit
BACKUP_SUCCESS=false

cleanup_on_exit() {
    local exit_code=$?
    if [[ "$BACKUP_SUCCESS" != true ]]; then
        echo "ERROR: Backup was not completed successfully (exit code ${exit_code}). Cleaning up partial artifacts..." >&2
        rm -f "$BACKUP_FILE" "$CHECKSUM_FILE"
    fi
    exit $exit_code
}

trap cleanup_on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "=== LedgerGuard Operational Database Backup ==="
echo "Timestamp:        ${TIMESTAMP}"
echo "Database:         ${DB_NAME}"
echo "Database Role:    ${DB_USER}"
echo "Compose File:     ${COMPOSE_FILE}"
echo "Destination:      ${BACKUP_FILE}"

# 1. Verify PostgreSQL container service is running and healthy/ready
if ! docker compose -f "$COMPOSE_FILE" exec -T postgres pg_isready -d "$DB_NAME" -U "$DB_USER" >/dev/null 2>&1; then
    echo "ERROR: PostgreSQL service in ${COMPOSE_FILE} is not ready or unreachable for database '${DB_NAME}' as user '${DB_USER}'." >&2
    exit 1
fi

PG_VERSION=$(docker compose -f "$COMPOSE_FILE" exec -T postgres postgres --version | tr -d '\r')
echo "PostgreSQL ver:   ${PG_VERSION}"

# 2. Execute pg_dump custom format with clean ownership/privilege semantics
echo "Executing pg_dump (-Fc --no-owner --no-privileges)..."
docker compose -f "$COMPOSE_FILE" exec -T postgres \
    pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc --no-owner --no-privileges > "$BACKUP_FILE"

# Verify file was generated and is non-empty
if [[ ! -s "$BACKUP_FILE" ]]; then
    echo "ERROR: Backup file ${BACKUP_FILE} is missing or empty." >&2
    exit 1
fi

BACKUP_SIZE=$(wc -c < "$BACKUP_FILE" | tr -d ' ')
echo "Backup size:      ${BACKUP_SIZE} bytes"

# 3. Verify archive readability using pg_restore --list before declaring success
echo "Verifying archive table of contents (pg_restore --list)..."
if ! docker compose -f "$COMPOSE_FILE" exec -T postgres pg_restore --list < "$BACKUP_FILE" >/dev/null; then
    echo "ERROR: Generated backup archive failed TOC integrity inspection with pg_restore --list." >&2
    exit 1
fi

# 4. Generate SHA-256 sidecar checksum
echo "Generating SHA-256 checksum sidecar..."
if command -v sha256sum >/dev/null 2>&1; then
    (cd "$OUTPUT_DIR" && sha256sum "$(basename "$BACKUP_FILE")" > "$(basename "$CHECKSUM_FILE")")
elif command -v shasum >/dev/null 2>&1; then
    (cd "$OUTPUT_DIR" && shasum -a 256 "$(basename "$BACKUP_FILE")" > "$(basename "$CHECKSUM_FILE")")
else
    echo "ERROR: Neither sha256sum nor shasum is available to compute checksum." >&2
    exit 1
fi

CHECKSUM_VALUE=$(awk '{print $1}' "$CHECKSUM_FILE")
echo "SHA-256 Checksum: ${CHECKSUM_VALUE}"

# Mark success so EXIT trap does not purge files
BACKUP_SUCCESS=true

echo "=== Backup Completed Successfully ==="
echo "Archive:          ${BACKUP_FILE}"
echo "Checksum Sidecar: ${CHECKSUM_FILE}"

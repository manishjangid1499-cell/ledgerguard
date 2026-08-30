#!/usr/bin/env bash
set -euo pipefail

# ====================================================================
# LedgerGuard PostgreSQL Initialization Script
# Executed on first-time initialization of an empty PostgreSQL data volume.
# Reads credentials safely from container environment variables.
# ====================================================================

# 1. Validate required environment variables are provided
: "${POSTGRES_USER:?POSTGRES_USER must be set in container environment}"
: "${POSTGRES_DB:?POSTGRES_DB must be set in container environment}"
: "${LEDGERGUARD_DB_USER:?LEDGERGUARD_DB_USER must be set in container environment}"
: "${LEDGERGUARD_DB_PASSWORD:?LEDGERGUARD_DB_PASSWORD must be set in container environment}"
: "${PSP_DB_USER:?PSP_DB_USER must be set in container environment}"
: "${PSP_DB_PASSWORD:?PSP_DB_PASSWORD must be set in container environment}"
: "${NOTIFICATION_DB_USER:?NOTIFICATION_DB_USER must be set in container environment}"
: "${NOTIFICATION_DB_PASSWORD:?NOTIFICATION_DB_PASSWORD must be set in container environment}"

# 2. Execute role and database creation using psql parameterized variables
psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  -v main_db="$POSTGRES_DB" \
  -v lg_user="$LEDGERGUARD_DB_USER" \
  -v lg_pass="$LEDGERGUARD_DB_PASSWORD" \
  -v psp_user="$PSP_DB_USER" \
  -v psp_pass="$PSP_DB_PASSWORD" \
  -v notif_user="$NOTIFICATION_DB_USER" \
  -v notif_pass="$NOTIFICATION_DB_PASSWORD" <<-'EOSQL'

  -- Create application login roles with respective passwords
  CREATE ROLE :"lg_user" WITH LOGIN PASSWORD :'lg_pass';
  CREATE ROLE :"psp_user" WITH LOGIN PASSWORD :'psp_pass';
  CREATE ROLE :"notif_user" WITH LOGIN PASSWORD :'notif_pass';

  -- Create dedicated logical databases with explicit role ownership
  CREATE DATABASE psp_simulator OWNER :"psp_user";
  CREATE DATABASE notification_worker OWNER :"notif_user";

  -- Assign primary database ownership to the ledgerguard application role
  ALTER DATABASE :"main_db" OWNER TO :"lg_user";

  -- Enforce database connection isolation across application roles
  REVOKE CONNECT ON DATABASE :"main_db" FROM PUBLIC;
  GRANT CONNECT ON DATABASE :"main_db" TO :"lg_user";

  REVOKE CONNECT ON DATABASE psp_simulator FROM PUBLIC;
  GRANT CONNECT ON DATABASE psp_simulator TO :"psp_user";

  REVOKE CONNECT ON DATABASE notification_worker FROM PUBLIC;
  GRANT CONNECT ON DATABASE notification_worker TO :"notif_user";
EOSQL

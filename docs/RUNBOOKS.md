# LedgerGuard Operational Runbooks & Disaster Recovery Procedures

## 1. Architectural & Operational Framework

### 1.1 Disaster Recovery Philosophy
In financial ledger systems, **Data Integrity Strictly Supercedes Availability**. Restoring an operational service on top of corrupted ledger entries, unbalanced journals, or drifted balance snapshots is unacceptable. If an invariant check fails during recovery, the recovery procedure must immediately halt.

LedgerGuard disaster recovery separates verification into two distinct operational regimes:
* **Mode A (Integrated Restore Self-Consistency):** Applied universally to all database restores (disaster recovery, cold restores, historical backups). Validates internal mathematical and relational invariants of the restored database itself without requiring access to a live source database.
* **Mode B (Controlled Source-Equivalence Drill):** Applied during scheduled recovery drills where source writers are cleanly quiesced prior to taking the backup. Compares post-quiesce/pre-backup source baseline metrics against post-restore target metrics to prove deterministic source-equivalence of critical ledger facts.

### 1.2 Core Financial Invariant & Monetary Model
All balance computations in LedgerGuard conform to strict double-entry principles:
* **Currency:** Strictly `INR` across all ledger accounts, journals, payments, holds, funding operations, and payouts.
* **Double-Entry Balance:** Every posted journal transaction satisfies:
  $$\sum \text{Debits} = \sum \text{Credits} \quad (\text{with } \text{entry count} \ge 2, \text{debits} \ge 1, \text{credits} \ge 1)$$
* **Global Zero-Sum:** Across all posted transactions:
  $$\sum \text{all Debits} - \sum \text{all Credits} = 0$$
* **Account-Type Normal Balances:** Account types possess distinct normal balance semantics based on fundamental accounting rules:
  * **Credit-Normal Accounts:**
    * `CUSTOMER` (User wallet liability)
    * `MERCHANT` (Merchant payable liability)
    * `PLATFORM_FEES` (Earned revenue)
    * **Formula:** $\text{Balance} = \sum(\text{CREDIT}) - \sum(\text{DEBIT})$
  * **Debit-Normal Accounts:**
    * `PSP_CLEARING` (Clearing receivable asset)
    * `PLATFORM_RESERVE` (Operational liquidity asset)
    * **Formula:** $\text{Balance} = \sum(\text{DEBIT}) - \sum(\text{CREDIT})$
* **Snapshot Consistency:** In `ledger_balance_snapshots`, each account has exactly one row keyed by `ledger_account_id` where `balance_minor` must equal the sum of posted journal entries according to the account's normal balance formula.

### 1.3 Database Role, Grant & Ownership Topology
The PostgreSQL database cluster enforces strict service isolation across logical databases:

| Database | Dedicated Owner Role | Application Consumer | Public Connect |
| :--- | :--- | :--- | :--- |
| `ledgerguard` | `ledgerguard_app` | `ledgerguard-api` | Revoked |
| `psp_simulator` | `psp_simulator_app` | `psp-simulator` | Revoked |
| `notification_worker` | `notification_worker_app` | `notification-worker` | Revoked |

* **Application Role Limits:** The role `ledgerguard_app` has `LOGIN` permissions only. It is **never granted `SUPERUSER`**, **`CREATEDB`**, or **`CREATEROLE`** privileges.
* **Database Ownership:** In production initialization (`infrastructure/postgres/init/01-create-databases.sh`), `ledgerguard` is owned by `ledgerguard_app`.
* **Cross-Database Isolation:** `CONNECT` on each database is revoked from `PUBLIC` and granted solely to its owning application role.

### 1.4 Backup Artifact Security & Handling
PostgreSQL backup dumps (`.dump` and `.sql`) contain sensitive financial records, account balances, PII (user emails, password hashes), and audit records:
1. **Filesystem Permissions:** Restrict access immediately upon generation (`umask 077` or `chmod 0600`).
2. **Encryption:** Encrypt backups at rest and in transit using organizational KMS keys or GPG prior to cloud/offsite archival.
3. **Access Control:** Storage buckets and archival media must be restricted to authorized Infrastructure and DBA personnel.
4. **Mandatory Integrity Sidecars:** Every backup archive must be paired with an immutable SHA-256 sidecar (`.dump.sha256`). Restores fail closed if sidecars are missing or fail verification.
5. **Exclusion from Source Control:** Backup files (`*.dump`, `*.sql`, `*.sha256`, `tmp/backups/`) must never be committed to Git.

---

## 2. PostgreSQL Operational Backup Procedure

### 2.1 Logical Database Backup (`scripts/backup-db.sh`)
The canonical backup mechanism uses PostgreSQL custom-format (`pg_dump -Fc --no-owner --no-privileges`) streamed directly from the PostgreSQL container via Docker Compose.

#### Script Execution
```bash
# Default local drill execution (backs up 'ledgerguard' from docker-compose.prod.yml to ./tmp/backups)
./scripts/backup-db.sh

# Production execution with explicit external directory
./scripts/backup-db.sh \
  --compose-file docker-compose.prod.yml \
  --dbname ledgerguard \
  --user ledgerguard_app \
  --output-dir /var/backups/ledgerguard
```

#### What `backup-db.sh` Executes
1. Verifies connectivity to the PostgreSQL container using `pg_isready`.
2. Emits `pg_dump -U ledgerguard_app -d ledgerguard -Fc --no-owner --no-privileges` into a timestamped file:
   `tmp/backups/ledgerguard_YYYYMMDD_HHMMSSZ.dump` (or specified `--output-dir`).
3. Inspects archive integrity by running `pg_restore --list` against the generated file. If TOC reading fails, the incomplete archive is purged and the script exits with an error.
4. Calculates SHA-256 checksum and generates a sidecar file:
   `tmp/backups/ledgerguard_YYYYMMDD_HHMMSSZ.dump.sha256`
5. Features an `EXIT` trap that cleans up partial or incomplete dumps if any step terminates abnormally.

#### Lifecycle & Retention
`backup-db.sh` deliberately **never deletes old backups**. Retention, pruning, and lifecycle policies are managed externally by enterprise storage lifecycle policies or backup infrastructure.

### 2.2 Cluster Globals Backup Procedure
A logical database dump (`pg_dump`) backs up only a single database (`ledgerguard`). It does **not** back up cluster-wide global objects such as database roles, role memberships, or tablespaces.

#### Globals Extraction (DBA Maintenance Only)
```bash
# Execute as postgres superuser
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_dumpall -U postgres --globals-only > tmp/backups/globals.sql

# Immediately restrict permissions
chmod 0600 tmp/backups/globals.sql

# Generate checksum
sha256sum tmp/backups/globals.sql > tmp/backups/globals.sql.sha256
```

> [!CAUTION]
> **Extreme Sensitivity:** `globals.sql` contains SCRAM-SHA-256 password hashes of all administrative and application roles. It must be encrypted immediately, stored with strict 0600 permissions, never committed to source control, and restricted strictly to authorized DBA staff.

---

## 3. PostgreSQL Disaster Recovery & Restore Procedure

### 3.1 Exact Restore Ownership Model
To avoid broad, dangerous commands such as `REASSIGN OWNED BY postgres TO ledgerguard_app` (which can fail or reassign shared cluster objects), LedgerGuard restores follow a clean, deterministic ownership model:

1. **Target Creation by Administrative Role:** The administrative superuser (`postgres`) creates the fresh target database with `OWNER = ledgerguard_app`:
   ```sql
   CREATE DATABASE "ledgerguard_phase40_restore" OWNER "ledgerguard_app";
   REVOKE CONNECT ON DATABASE "ledgerguard_phase40_restore" FROM PUBLIC;
   GRANT CONNECT ON DATABASE "ledgerguard_phase40_restore" TO "ledgerguard_app";
   ```
2. **Restoration as Application Role:** `pg_restore` connects directly to the target database as `ledgerguard_app`:
   ```bash
   pg_restore -U ledgerguard_app -d ledgerguard_phase40_restore \
     --no-owner --no-privileges --single-transaction --exit-on-error < archive.dump
   ```
3. **Native Ownership:** Because `pg_restore` connects as `ledgerguard_app` into a database owned by `ledgerguard_app`, all restored tables, indexes, views, sequences, and functions/procedures are created natively with `ledgerguard_app` as owner.
4. **Post-Restore Verification:** The restore script explicitly verifies:
   * Target database owner is `ledgerguard_app`.
   * Public schema objects (tables, views, sequences, and functions/procedures) owned by other roles count equals `0`.
   * Role `ledgerguard_app` possesses `false|false|false` for `rolsuper`, `rolcreaterole`, and `rolcreatedb`.

### 3.2 Automated Restore & Mode A Validation (`scripts/restore-db.sh`)
The script `scripts/restore-db.sh` automates fail-closed checksum verification, target creation, restoration, and mandatory Mode A financial validation.

#### Standard Drill / Restore Execution
```bash
# Standard restore to an isolated target database
./scripts/restore-db.sh \
  -f tmp/backups/ledgerguard_20260911_120000Z.dump \
  -t ledgerguard_drill_20260911

# Validate an existing restored database without re-restoring
./scripts/restore-db.sh \
  --validate-only \
  -t ledgerguard_drill_20260911
```

#### Safe Default Semantics
* **Authoritative Protection:** The script strictly rejects any attempt to target `ledgerguard` directly.
* **Fail-Closed Checksum:** Restoring without a valid, non-empty `.sha256` sidecar matching the archive is impossible; the script aborts before creating any database.
* **Target Collision Protection:** If the target database already exists, the script fails safely by default.
* **Destructive Replacement:** To deliberately drop and recreate an existing target database during a drill, the operator must provide both `--force-destructive-drop` AND `--confirm-target-db <TARGET_NAME>`.

### 3.3 Mode A Invariant Suite (Embedded in `restore-db.sh`)
Mode A executes directly after restore and validates the following invariants:

1. **Schema Completeness:** Verifies that all 20 authoritative tables exist in `public`:
   `users`, `refresh_tokens`, `ledger_accounts`, `journal_transactions`, `journal_entries`, `ledger_balance_snapshots`, `idempotency_records`, `transfers`, `payments`, `refunds`, `balance_holds`, `outbox_events`, `funding_operations`, `payouts`, `provider_events`, `reconciliation_runs`, `reconciliation_items`, `reconciliation_cases`, `audit_events`, `flyway_schema_history`.
2. **Flyway History Verification:**
   * Exact set {1..17} all present and marked `success = true`.
   * Total migration count equals `17`.
   * Highest numeric migration version is `17`.
   * Zero migrations exist with version $> 17$ (no V18).
3. **POSTED Journal Structure:**
   * For every `POSTED` transaction: $\text{entries} \ge 2$, $\text{debits} \ge 1$, $\text{credits} \ge 1$.
   * Per-transaction $\sum \text{Debits} = \sum \text{Credits}$ (evaluated with `amount_minor::numeric`).
   * `DRAFT` journals are excluded from balance checks.
4. **Global POSTED Zero-Sum Balance:**
   * Across all `POSTED` journals:
     $$\sum_{\text{POSTED}} \text{Debits} - \sum_{\text{POSTED}} \text{Credits} = 0$$
5. **Balance Snapshot Parity:**
   * Reconstructs balance from `POSTED` journal entries for every account using normal balances:
     * `CUSTOMER`, `MERCHANT`, `PLATFORM_FEES`: $\sum \text{Credit} - \sum \text{Debit}$
     * `PSP_CLEARING`, `PLATFORM_RESERVE`: $\sum \text{Debit} - \sum \text{Credit}$
   * Reconstructed balance must match `ledger_balance_snapshots.balance_minor` with zero missing rows and zero discrepancies.
6. **Required Database Triggers:**
   * Verifies all 21 relation-trigger pairs exist and are enabled (`tgenabled = 'O'` and not internal):
     * `audit_events.trg_audit_events_no_truncate`
     * `audit_events.trg_audit_events_no_update_delete`
     * `balance_holds.trg_balance_holds_integrity`
     * `funding_operations.trg_funding_operations_lifecycle_and_immutability`
     * `idempotency_records.trg_idempotency_records_immutability`
     * `journal_entries.trg_journal_entries_immutability`
     * `journal_transactions.trg_journal_transactions_balance_check`
     * `journal_transactions.trg_journal_transactions_immutability`
     * `journal_transactions.trg_journal_transactions_update_snapshots`
     * `ledger_accounts.trg_ledger_accounts_init_snapshot`
     * `outbox_events.trg_outbox_events_integrity`
     * `payments.trg_payments_lifecycle_and_immutability`
     * `payouts.trg_payouts_lifecycle_and_immutability`
     * `provider_events.trg_provider_events_immutability`
     * `reconciliation_cases.trg_recon_cases_lifecycle`
     * `reconciliation_items.trg_recon_items_auto_create_case`
     * `reconciliation_items.trg_recon_items_immutability`
     * `reconciliation_runs.trg_recon_runs_lifecycle`
     * `refunds.trg_refunds_integrity`
     * `transfers.trg_transfers_immutability`
     * `transfers.trg_transfers_validate_journal_posted`
7. **Outbox Status & Trace Context:**
   * Status values must only be `PENDING` or `PUBLISHED`.
   * V17 OpenTelemetry tracing columns (`traceparent`, `tracestate`, `correlation_id`) must exist.

### 3.4 Mode B Controlled Source-Equivalence Drill
In a planned disaster recovery exercise, Mode B proves selected deterministic source-equivalence facts between the post-quiesce source database and the restored database.

#### Mode B Procedure
1. **Quiesce Writers:** Stop incoming traffic by halting edge proxies or stopping `ledgerguard-api` and worker containers.
2. **Capture Deterministic Post-Quiesce Source Baseline:**
   ```sql
   -- Execute against source database 'ledgerguard'
   SELECT count(*) AS user_count FROM users;
   SELECT count(*) AS journal_count FROM journal_transactions WHERE status = 'POSTED';
   SELECT count(*) AS entry_count FROM journal_entries;
   SELECT coalesce(sum(amount_minor::numeric), 0) AS total_entry_volume FROM journal_entries;
   SELECT count(*) AS snapshot_count FROM ledger_balance_snapshots;
   SELECT coalesce(sum(balance_minor::numeric), 0) AS total_snapshot_balance FROM ledger_balance_snapshots;
   ```
3. **Execute Backup:** Run `./scripts/backup-db.sh`.
4. **Execute Restore:** Run `./scripts/restore-db.sh -f <BACKUP_FILE> -t ledgerguard_drill`.
5. **Mode A Automated Pass:** Confirm Mode A validation passes automatically.
6. **Compare Facts:** Run the exact queries from Step 2 against `ledgerguard_drill` and assert exact numeric equivalence.

---

## 4. Point-in-Time Recovery (PITR) Architecture & Boundaries

### 4.1 Logical Backup vs. True PITR
* **Logical Backup (`pg_dump`):** Takes a snapshot of the database at the single point in time when the dump command runs. Restoring a logical backup returns the database to that exact historical moment; any transaction executed after the dump cannot be recovered from the dump alone.
* **Point-in-Time Recovery (PITR):** Enables restoring a database to any arbitrary second (or specific transaction/LSN) between a base backup and the present.

### 4.2 Prerequisites for PostgreSQL PITR
True PITR requires dedicated infrastructure not enabled in LedgerGuard's local development Compose topology:
1. **Physical Base Backup:** Created via `pg_basebackup` (taking a raw filesystem/block copy of the database cluster).
2. **Continuous WAL Archiving:** Configured in `postgresql.conf`:
   ```ini
   wal_level = replica
   archive_mode = on
   archive_command = 'cp %p /var/lib/postgresql/wal_archive/%f'  # or upload to S3/GCS
   ```
3. **Continuous WAL Storage:** A durable external storage location retaining all WAL segments generated since the base backup.
4. **Recovery Configuration:** Upon restoring the physical base backup, specifying:
   ```ini
   restore_command = 'cp /var/lib/postgresql/wal_archive/%f %p'
   recovery_target_time = '2026-09-11 14:30:00 UTC'
   recovery_target_action = 'promote'
   ```

### 4.3 Architectural Boundary Notice
LedgerGuard Phase 40 authoritatively documents disaster recovery runbooks and scripts. It **does not modify `postgresql.conf` or `docker-compose.prod.yml` to force continuous WAL archiving**.
* **Managed Cloud PostgreSQL:** Managed PostgreSQL services (such as AWS RDS or GCP Cloud SQL) MAY expose provider-managed automated backup and point-in-time recovery capabilities depending on service tier and configuration.
* **Self-Hosted PostgreSQL & Patroni:** Self-hosted PostgreSQL deployments, including Patroni-based high-availability clusters, still require explicitly configured physical backup tooling (e.g. pgBackRest, WAL-G, or Barman) and continuous WAL archival/recovery configurations. Patroni high-availability orchestration by itself is not a backup or PITR mechanism.
* **Phase Scope:** Phase 40 establishes logical backup and restore automation and operational procedures; it does not introduce physical WAL archiving infrastructure into the local repository topology.

### 4.4 Recovery Metrics Terminology
* **Recovery Point Objective (RPO):** The maximum targeted duration of data loss measured back in time from a failure. In a periodic logical backup architecture, the maximum data loss window is bounded by the interval between successful backups.
* **Recovery Time Objective (RTO):** The duration required from declaration of disaster until the restored service is verified and accepting live traffic.

---

## 5. End-to-End Disaster Recovery Orchestration & Sequencing

During an operational disaster recovery event, restoring services in the wrong order can cause duplicate event publishing, invalid balance reads, or poison-pill retries. The following authoritative 17-step sequence must be followed:

```
[1. Quarantine Traffic] ──> [2. Stop Writers/Workers] ──> [3. Restore Database]
                                                                  │
[6. Promote Restored DB] <── [5. Gate: Mode A Valid] <───────────┘
          │
          ▼
[7. Start API (Publisher OFF)] ──> [8. Verify API Health] ──> [9. Verify PSP Boundary]
                                                                        │
[12. Start Worker] <── [11. Verify Kafka] <── [10. Level-3 Recon] ◄─────┘
          │
          ▼
[13. Verify Consumer Group] ──> [14. Enable Outbox Publisher] ──> [15. Drain PENDING Outbox]
                                                                        │
[17. Restore Edge Traffic] <── [16. Verify Worker Lag] ◄────────────────┘
```

### Detailed Sequence
1. **Quarantine Edge Traffic:**
   * Stop public traffic ingress.
   * *Networking Fact:* Stopping `nginx-edge` terminates listening sockets; clients will receive TCP connection refusal/reset. If an organization requires a user-facing HTTP 503 maintenance page, that page must be served by an independent external load balancer or CDN layer upstream of `nginx-edge`.
2. **Stop Application Writers and Consumers:**
   * Stop `ledgerguard-api` and `notification-worker` to prevent writes during restoration:
     ```bash
     docker compose -f docker-compose.prod.yml stop ledgerguard-api notification-worker
     ```
3. **Execute Database Restore to Isolated Target:**
   * Run `scripts/restore-db.sh` targeting an isolated recovery database (e.g. `ledgerguard_recovery_20260911`).
4. **Execute Mode A Validation:**
   * Automated verification runs as part of `restore-db.sh`.
5. **Validation Gate:**
   * If Mode A validation fails, **HALT IMMEDIATELY**. Do not proceed to cutover on an unverified database.
6. **Promote Restored Database into Production Service Name:**
   * Terminate any remaining connections to `ledgerguard`:
     ```bash
     docker compose -f docker-compose.prod.yml exec -T postgres psql -U postgres -d postgres -c \
       "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'ledgerguard' AND pid <> pg_backend_pid();"
     ```
   * Rename the original/corrupted database for forensic preservation (never drop immediately):
     ```bash
     docker compose -f docker-compose.prod.yml exec -T postgres psql -U postgres -d postgres -c \
       "ALTER DATABASE \"ledgerguard\" RENAME TO \"ledgerguard_corrupted_$(date +%Y%m%d_%H%M%S)\";"
     ```
   * Terminate any remaining connections to the recovery database:
     ```bash
     docker compose -f docker-compose.prod.yml exec -T postgres psql -U postgres -d postgres -c \
       "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'ledgerguard_recovery_20260911' AND pid <> pg_backend_pid();"
     ```
   * Rename the verified recovery database into production name `ledgerguard`:
     ```bash
     docker compose -f docker-compose.prod.yml exec -T postgres psql -U postgres -d postgres -c \
       "ALTER DATABASE \"ledgerguard_recovery_20260911\" RENAME TO \"ledgerguard\";"
     ```
7. **Start `ledgerguard-api` with Outbox Publisher Disabled:**
   * Use canonical Docker Compose command with environment variable override:
     ```bash
     LEDGERGUARD_OUTBOX_PUBLISHER_ENABLED=false docker compose -f docker-compose.prod.yml up -d ledgerguard-api
     ```
8. **Verify API Database Connectivity & Health:**
   * Check `/actuator/health` on `ledgerguard-api` to ensure database connectivity and migration state are green.
9. **Verify Provider / PSP Boundary Connectivity:**
   * In portfolio topology: verify `psp-simulator` is running and healthy (TCP socket on port 8081).
   * In real production: verify network reachability and credentials for external PSP APIs.
10. **Perform Level-3 Reconciliation:**
    * Execute reconciliation against the provider to detect any ambiguous operations executed prior to the disaster.
11. **Verify Kafka Broker Health:**
    * Ensure Kafka cluster is operational and topics exist.
12. **Start `notification-worker`:**
    * Start the notification worker service:
      ```bash
      docker compose -f docker-compose.prod.yml up -d notification-worker
      ```
13. **Verify Consumer Group Registration:**
    * Confirm `ledgerguard-notification-worker-v1` has joined the consumer group and assigned partitions cleanly.
14. **Enable Outbox Publisher:**
    * Restart `ledgerguard-api` with normal configuration (default publisher enabled):
      ```bash
      docker compose -f docker-compose.prod.yml up -d --force-recreate ledgerguard-api
      ```
15. **Monitor PENDING Outbox Drain:**
    * Monitor `outbox_events` until count of `status = 'PENDING'` reaches `0`:
      ```sql
      SELECT count(*) FROM outbox_events WHERE status = 'PENDING';
      ```
16. **Verify Consumer Lag & Processing:**
    * Inspect consumer group lag to ensure published events are processed without unhandled exceptions.
17. **Restore Public Edge Traffic:**
    * Restart `nginx-edge` and resume live customer traffic.

---

## 6. Kafka Operational Runbook & Lag Remediation

### 6.1 Authoritative Kafka Configuration

| Parameter | Authoritative Value | Source Location |
| :--- | :--- | :--- |
| **Domain Events Topic** | `ledgerguard.domain-events.v1` | `application.yml` |
| **Dead Letter Topic (DLT)** | `ledgerguard.domain-events.v1.DLT` | `application.yml` |
| **Consumer Group ID** | `ledgerguard-notification-worker-v1` | `application.yml` |
| **Topic Partitions** | `3` | `KafkaTopicConfig.java` |
| **Listener Concurrency** | `3` | `KafkaConsumerConfig.java` |
| **Max Poll Records** | `10` | `KafkaConsumerConfig.java` |
| **AckMode** | `ContainerProperties.AckMode.RECORD` | `KafkaConsumerConfig.java` |
| **Auto-Commit** | `false` (`ENABLE_AUTO_COMMIT_CONFIG`) | `KafkaConsumerConfig.java` |

### 6.2 Lag Inspection Procedure
To inspect consumer lag across partitions in the running Kafka container:
```bash
docker compose -f docker-compose.prod.yml exec -T kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 \
  --describe \
  --group ledgerguard-notification-worker-v1
```

* **Interpreting Output:**
  * `CURRENT-OFFSET`: Last offset processed and committed by the worker.
  * `LOG-END-OFFSET`: Highest offset written to the topic by the outbox publisher.
  * `LAG`: The number of unconsumed messages (`LOG-END-OFFSET` - `CURRENT-OFFSET`).

### 6.3 Consumer Scaling Semantics & Rules
> [!IMPORTANT]
> **Partition to Thread Mapping:** The topic `ledgerguard.domain-events.v1` has **3 partitions**, and each `notification-worker` instance has **`concurrency = 3`**. Therefore, **a single healthy worker instance already allocates 3 consumer threads**, perfectly saturating all 3 partitions.

* **Scaling Misconception:** Scaling the service to 3 container replicas (`--scale notification-worker=3`) creates 9 consumer threads for only 3 partitions. Because Kafka assigns each partition to at most one consumer thread in a group, **6 consumer threads will remain completely idle**.
* **Proper Remediation Priorities for Lag:**
  1. Inspect worker application logs for database connection pool exhaustion or lock timeouts.
  2. Inspect database query latency in `notification_worker` database.
  3. Inspect DLT topic (`ledgerguard.domain-events.v1.DLT`) for poison-pill events causing retry loops.
  4. If event throughput consistently exceeds 3-thread processing capacity, a formal topology change is required: increase partition count on `ledgerguard.domain-events.v1` before scaling worker containers.

### 6.4 Safe Offset Reset Procedure
If consumer processing fails catastrophically or corrupted events must be skipped/replayed:

> [!WARNING]
> * `--to-latest`: Permanently skips all unprocessed events currently in lag.
> * `--to-earliest`: Replays all events in retention history.
> * Offset resets must **never** be performed while the consumer group is running!

#### Step-by-Step Offset Reset:
1. **Stop Notification Worker:**
   ```bash
   docker compose -f docker-compose.prod.yml stop notification-worker
   ```
2. **Execute Dry-Run First:**
   ```bash
   docker compose -f docker-compose.prod.yml exec -T kafka \
     /opt/kafka/bin/kafka-consumer-groups.sh \
     --bootstrap-server kafka:9092 \
     --group ledgerguard-notification-worker-v1 \
     --topic ledgerguard.domain-events.v1 \
     --reset-offsets --to-offset <TARGET_OFFSET> \
     --dry-run
   ```
3. **Execute Reset:**
   ```bash
   docker compose -f docker-compose.prod.yml exec -T kafka \
     /opt/kafka/bin/kafka-consumer-groups.sh \
     --bootstrap-server kafka:9092 \
     --group ledgerguard-notification-worker-v1 \
     --topic ledgerguard.domain-events.v1 \
     --reset-offsets --to-offset <TARGET_OFFSET> \
     --execute
   ```
4. **Restart Notification Worker:**
   ```bash
   docker compose -f docker-compose.prod.yml up -d notification-worker
   ```
* **Deduplication Safeguard:** `notification-worker` implements database-backed event deduplication (`processed_events` table). Replaying previously processed events will safely record an `IGNORED` outcome without creating duplicate notification deliveries.

---

## 7. External Provider (PSP) Boundary & Reconciliation Runbook

### 7.1 Provider Health Verification
* **Portfolio Development Environment:**
  * `psp-simulator` does not expose an HTTP actuator health endpoint.
  * Container health is verified via TCP readiness on port `8081` (as configured in `docker-compose.prod.yml`):
    ```bash
    docker compose -f docker-compose.prod.yml exec -T psp-simulator \
      bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081' && echo "PSP Simulator TCP 8081 READY"
    ```
* **Production Environment:**
  * The PSP is an external third-party payment gateway (e.g. banking rails, card networks). LedgerGuard does not manage or deploy the external provider.
  * Verify external network egress and third-party gateway status before running settlement reconciliation.

### 7.2 Core URLs
* **PSP Internal Base URL:** `http://psp-simulator:8081`
* **PSP Webhook Callback URL:** `http://ledgerguard-api:8080/api/provider/webhooks`

### 7.3 Reconciliation Architecture & Operational API
Reconciliation is structured into three distinct operational levels:
1. **Level 1 (Journal Balance):** Evaluates double-entry invariants across `journal_transactions` and `journal_entries`. (Requires PostgreSQL only).
2. **Level 2 (Snapshot Consistency):** Evaluates balance snapshot parity against journal history. (Requires PostgreSQL only).
3. **Level 3 (Provider Settlement):** Evaluates internal `funding_operations` and `payouts` against external provider status. (Requires external provider reachability).

#### Operational API Endpoints (`ReconciliationController.java`)
All operational reconciliation endpoints require authentication with role `OPS`:
* **List Reconciliation Runs:** `GET /api/reconciliation/runs?page=0&size=20`
* **Get Run Details:** `GET /api/reconciliation/runs/{runId}`
* **Get Run Discrepancy Items:** `GET /api/reconciliation/runs/{runId}/items`
* **List Investigation Cases:** `GET /api/reconciliation/cases?status=OPEN`
* **Claim Investigation Case:** `POST /api/reconciliation/cases/{caseId}/claim`
* **Auto-Repair Drifted Snapshot:** `POST /api/reconciliation/cases/{caseId}/repair-snapshot`
* **Resolve Case Manually:** `POST /api/reconciliation/cases/{caseId}/resolve` (Body: `{"resolutionNote": "Verified against bank settlement file"}`)

---

## 8. Incident Response Runbook & Drill Checklists

### 8.1 Periodic Recovery Drill Checklist (Mode B Controlled Drill)
> [!NOTE]
> In an online routine verification drill (Mode A only), backups are validated for self-consistency without quiescing traffic. In contrast, a **Mode B drill** proves deterministic source-equivalence and requires writer quiescing before baseline capture and backup.

* [ ] 1. **Notify Stakeholders:** Inform engineering and operational stakeholders of scheduled Mode B DR drill.
* [ ] 2. **Verify Environment:** Confirm PostgreSQL, Docker host storage, and backup directory (`./tmp/backups` or `/var/backups`) are ready and healthy.
* [ ] 3. **Quiesce Writers / Ingress:** Stop live writers and ingress to freeze application state:
      `docker compose -f docker-compose.prod.yml stop nginx-edge ledgerguard-api notification-worker`
* [ ] 4. **Capture Post-Quiesce Source Baseline:** Query deterministic metrics on source database `ledgerguard` (user counts, posted journal counts, journal entry counts and volume sums, snapshot counts and balance sums).
* [ ] 5. **Execute Backup:** Run `./scripts/backup-db.sh` to generate timestamped `.dump` and `.sha256`.
* [ ] 6. **Verify Dump & Checksum:** Confirm backup file is non-empty, table of contents is readable (`pg_restore --list`), and SHA-256 sidecar is verified.
* [ ] 7. **Restore to Isolated Target Database:** Run `./scripts/restore-db.sh -f <BACKUP_FILE> -t ledgerguard_drill_$(date +%Y%m%d)`.
* [ ] 8. **Verify Mode A Passes:** Confirm all 10 Mode A automated self-consistency checks pass on target database.
* [ ] 9. **Execute Mode B Comparison:** Execute the baseline queries from Step 4 against `ledgerguard_drill_$(date +%Y%m%d)` and assert exact numerical parity.
* [ ] 10. **Clean Up Drill Database:** Drop the temporary drill database (`DROP DATABASE "ledgerguard_drill_$(date +%Y%m%d)";`).
* [ ] 11. **Restore / Resume Writers & Traffic:** Restart application services and ingress as appropriate:
      `docker compose -f docker-compose.prod.yml up -d`
* [ ] 12. **Record Drill Outcome:** Document drill results, execution timings, and invariant validation proofs in the operational log.

### 8.2 Database Corruption Incident Response Checklist
* [ ] Declare operational incident.
* [ ] Quarantine edge traffic (stop `nginx-edge` / route upstream CDN to maintenance).
* [ ] Stop `ledgerguard-api` and `notification-worker` containers immediately.
* [ ] Locate the most recent verified backup archive and verify its mandatory SHA-256 checksum sidecar.
* [ ] Restore into isolated target database `ledgerguard_recovery` using `scripts/restore-db.sh`.
* [ ] Verify Mode A passes completely.
* [ ] Promote verified database into production service name:
      * Terminate active connections on `ledgerguard`:
        `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'ledgerguard' AND pid <> pg_backend_pid();`
      * Rename corrupted database for forensic investigation:
        `ALTER DATABASE "ledgerguard" RENAME TO "ledgerguard_corrupted_<TIMESTAMP>";`
      * Terminate active connections on `ledgerguard_recovery`:
        `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'ledgerguard_recovery' AND pid <> pg_backend_pid();`
      * Rename verified recovery database to production name:
        `ALTER DATABASE "ledgerguard_recovery" RENAME TO "ledgerguard";`
* [ ] Start `ledgerguard-api` with outbox publisher disabled:
      `LEDGERGUARD_OUTBOX_PUBLISHER_ENABLED=false docker compose -f docker-compose.prod.yml up -d ledgerguard-api`
* [ ] Verify health at `/actuator/health`.
* [ ] Verify provider connectivity (`psp-simulator` TCP port 8081 or external PSP).
* [ ] Run Level-3 reconciliation.
* [ ] Start `notification-worker` and verify consumer group registration.
* [ ] Enable outbox publisher on `ledgerguard-api` (`docker compose -f docker-compose.prod.yml up -d --force-recreate ledgerguard-api`).
* [ ] Monitor outbox drain to `0` pending events.
* [ ] Re-enable edge traffic (`nginx-edge`).
* [ ] Conduct post-incident review.

# LedgerGuard Phase 39 Benchmarks

## 1. Objective
The objective of Phase 39 is to benchmark transaction throughput under varying concurrency levels, profile database connection pool contention versus database lock contention, evaluate deterministic lock-ordering resilience, and provide empirical evidence to evaluate HikariCP pool sizing for LedgerGuard.

---

## 2. Scope
- **Direct Service & Database Boundary:** These benchmarks measure direct execution through the Spring `@Transactional` service layer (`TransferService.createTransfer()`), Hibernate/JPA, HikariCP, and PostgreSQL 17.
- **NOT an HTTP/Tomcat Capacity Benchmark:** This benchmark does not drive traffic through Tomcat HTTP worker pools, servlet filters, or network sockets, nor does it test Bucket4j rate-limiting admission control.
- **No Production SLA Inference:** These measurements establish comparative architectural baselines on local developer hardware; they do not define production Service Level Agreements (SLAs).

---

## 3. Environment
All benchmark runs were executed on the following captured environment:

- **Operating System:** Windows 11 (Version 10.0, Architecture: amd64)
- **CPU / Available Processors:** 8 logical cores (`Runtime.getRuntime().availableProcessors() = 8`)
- **Java Runtime:** Oracle Corporation Java 21.0.2 (OpenJDK 64-Bit Server VM)
- **JVM Maximum Heap Memory:** 4,042 MB (4,238,344,192 bytes)
- **Database Engine:** PostgreSQL 17.11 on x86_64-pc-linux-musl (Alpine Linux Docker container via Testcontainers)
- **PostgreSQL Configuration:** `max_connections = 300`
- **Database Migrations:** Flyway V1–V17 applied cleanly; V18 absent
- **HikariCP Version:** Version managed by the project's Spring Boot dependency set
- **Effective HikariCP Settings (Runtime Verified via `HikariDataSource`):**
  - Pool Name: `HikariPool-1`
  - Maximum Pool Size: 10 (Baseline), 5, 15, 20 (Candidates)
  - Minimum Idle: Defaults to Maximum Pool Size (e.g. 10 for baseline)
  - Connection Timeout: 30,000 ms (30 s)
  - Idle Timeout: 600,000 ms (10 min)
  - Max Lifetime: 1,800,000 ms (30 min)
  - Keepalive Time: 120,000 ms (2 min)

---

## 4. Methodology
- **Process Isolation:** Each Hikari pool configuration (10, 5, 15, 20) was executed in a separate Maven JVM process with a fresh ephemeral PostgreSQL Testcontainer to prevent connection or cache contamination.
- **Warmup:** Following Spring context initialization, 20 unmeasured warmup operations were executed to prime JVM JIT compilation, Hikari connection allocations, and database shared buffers.
- **Measured Operations:** Every measured scenario executed exactly 100 transfer operations across 3 repetitions. The complete official benchmark suite comprises **8,100 successful measured transfer operations** (3,300 measured operations in the Pool-10 baseline scaling experiment and 4,800 measured operations in the controlled Hikari pool-comparison experiment). Unmeasured warmup operations are excluded from all totals and metric calculations.
- **Fixture Isolation:** Fresh UUID-keyed users, customer accounts, and funded ledger balances were provisioned for every single repetition.
- **Latency Calculation:** High-precision timers (`System.nanoTime()`) measured the duration of each individual `TransferService.createTransfer()` call (excluding fixture setup). Percentiles (p50, p95, p99) were calculated using deterministic nearest-rank indexing:
  $$\text{index} = \max\left(0, \min\left(\left\lceil \frac{P}{100} \times N \right\rceil - 1, N - 1\right)\right)$$
- **Non-Invasive Observer:** A dedicated JDBC connection created via `DriverManager` outside of Hikari sampled PostgreSQL lock waiters (`pg_stat_activity` where `wait_event_type = 'Lock'`) and database deadlocks (`pg_stat_database`) at ~20ms intervals without consuming benchmark pool connections.
- **Financial Invariant Validation:** Immediately following each repetition, `FinancialInvariantOracle` executed direct SQL assertions against PostgreSQL verifying journal structure, debit/credit zero-sum balance, snapshot reconstruction integrity, and participant money conservation.

---

## 5. Pool 10 Concurrency Scaling Baseline (Baseline Mode)

The full concurrency scaling curve for the default Pool Size 10 was captured under `baseline` mode across all three canonical workloads, exercising concurrency levels from 1 to 50 threads:

| Workload | Concurrency | Repetitions | Median TPS | Median p50 (ms) | Median p95 (ms) | Median p99 (ms) | Max Pending Threads | Max DB Lock Waiters | Deadlocks Delta | Failures | Financial Invariants |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `LOW_CONTENTION` | 1 | 3 | 20.58 | 40.65 | 67.19 | 69.55 | 0 | 0 | 0 | 0 | **PASSED** |
| `LOW_CONTENTION` | 5 | 3 | 98.17 | 48.25 | 65.05 | 86.47 | 0 | 0 | 0 | 0 | **PASSED** |
| `LOW_CONTENTION` | 10 | 3 | 90.44 | 84.48 | 235.65 | 247.89 | 0 | 0 | 0 | 0 | **PASSED** |
| `LOW_CONTENTION` | 20 | 3 | 133.30 | 102.12 | 224.59 | 279.82 | 10 | 0 | 0 | 0 | **PASSED** |
| `LOW_CONTENTION` | 50 | 3 | 149.19 | 247.09 | 470.93 | 519.59 | 40 | 0 | 0 | 0 | **PASSED** |
| `HOT_ACCOUNT` | 10 | 3 | 47.15 | 201.33 | 256.41 | 265.76 | 0 | 9 | 0 | 0 | **PASSED** |
| `HOT_ACCOUNT` | 20 | 3 | 35.66 | 443.05 | 901.54 | 909.07 | 10 | 9 | 0 | 0 | **PASSED** |
| `HOT_ACCOUNT` | 50 | 3 | 42.52 | 1073.06 | 1316.08 | 1329.86 | 40 | 9 | 0 | 0 | **PASSED** |
| `OPPOSING_TRANSFERS` | 10 | 3 | 45.17 | 207.21 | 306.17 | 313.38 | 0 | 9 | 0 | 0 | **PASSED** |
| `OPPOSING_TRANSFERS` | 20 | 3 | 43.20 | 440.60 | 531.46 | 551.28 | 10 | 9 | 0 | 0 | **PASSED** |
| `OPPOSING_TRANSFERS` | 50 | 3 | 54.81 | 811.84 | 911.12 | 912.87 | 40 | 9 | 0 | 0 | **PASSED** |

---

## 6. Fair HikariCP Pool Sizing Comparison (Pool-Comparison Mode)

To ensure a strictly fair, apples-to-apples comparison across candidate pool sizes, **Pools 5, 10, 15, and 20** were executed under identical `pool-comparison` mode.
In this mode, every JVM process executes the exact same pre-measurement workload history: 20 unmeasured warmup operations followed by identical measured workload sequences (`LOW_CONTENTION` at Concurrency 20 and 50, then `HOT_ACCOUNT` at Concurrency 20 and 50, each with 3 repetitions of 100 transactions).

All figures report the **median** across the 3 measured repetitions per configuration.

| Pool Size | Workload | Concurrency | Repetitions | Median TPS | Median p50 (ms) | Median p95 (ms) | Median p99 (ms) | Max Pending Threads | Max DB Lock Waiters | Deadlocks Delta | Failures | Financial Invariants |
| :---: | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **5** | `LOW_CONTENTION` | 20 | 3 | 65.34 | 245.81 | 507.09 | 669.05 | 15 | 0 | 0 | 0 | **PASSED** |
| **5** | `LOW_CONTENTION` | 50 | 3 | 106.00 | 360.05 | 710.37 | 835.01 | 45 | 0 | 0 | 0 | **PASSED** |
| **5** | `HOT_ACCOUNT` | 20 | 3 | 52.78 | 381.52 | 505.78 | 532.57 | 15 | 4 | 0 | 0 | **PASSED** |
| **5** | `HOT_ACCOUNT` | 50 | 3 | 42.32 | 1096.50 | 1224.20 | 1234.15 | 45 | 4 | 0 | 0 | **PASSED** |
| **10** | `LOW_CONTENTION` | 20 | 3 | 81.92 | 163.80 | 499.53 | 582.68 | 10 | 0 | 0 | 0 | **PASSED** |
| **10** | `LOW_CONTENTION` | 50 | 3 | 127.54 | 254.60 | 595.46 | 669.81 | 40 | 0 | 0 | 0 | **PASSED** |
| **10** | `HOT_ACCOUNT` | 20 | 3 | 48.97 | 415.88 | 476.49 | 572.73 | 10 | 9 | 0 | 0 | **PASSED** |
| **10** | `HOT_ACCOUNT` | 50 | 3 | 50.16 | 897.97 | 1027.10 | 1036.32 | 40 | 9 | 0 | 0 | **PASSED** |
| **15** | `LOW_CONTENTION` | 20 | 3 | 76.74 | 199.04 | 408.03 | 487.77 | 5 | 0 | 0 | 0 | **PASSED** |
| **15** | `LOW_CONTENTION` | 50 | 3 | 128.66 | 223.50 | 634.28 | 672.94 | 35 | 0 | 0 | 0 | **PASSED** |
| **15** | `HOT_ACCOUNT` | 20 | 3 | 44.34 | 397.11 | 595.73 | 612.44 | 5 | 14 | 0 | 0 | **PASSED** |
| **15** | `HOT_ACCOUNT` | 50 | 3 | 42.22 | 1030.80 | 1281.61 | 1302.51 | 35 | 14 | 0 | 0 | **PASSED** |
| **20** | `LOW_CONTENTION` | 20 | 3 | 98.71 | 193.04 | 239.94 | 255.72 | 1 | 5 | 0 | 0 | **PASSED** |
| **20** | `LOW_CONTENTION` | 50 | 3 | 156.45 | 185.68 | 514.34 | 589.98 | 30 | 0 | 0 | 0 | **PASSED** |
| **20** | `HOT_ACCOUNT` | 20 | 3 | 51.55 | 363.59 | 424.00 | 430.74 | 4 | 19 | 0 | 0 | **PASSED** |
| **20** | `HOT_ACCOUNT` | 50 | 3 | 43.72 | 968.43 | 1364.65 | 1372.10 | 30 | 19 | 0 | 0 | **PASSED** |

---

## 7. Workload A — Low Contention Observations
- **Throughput Scaling:** Under Pool Size 10, throughput scales smoothly from 20.58 TPS at concurrency 1 to 149.19 TPS at concurrency 50 in baseline mode.
- **Disjoint Account Locking Behavior:** The workload uses disjoint participant account pairs and is designed to minimize shared-account contention. Some PostgreSQL lock waiting was still observed in individual runs (such as `maxDatabaseLockWaiters = 5` in Pool 20 at concurrency 20), so the benchmark does not equate disjoint account topology with zero database locking.
- **Pool Saturation Dynamics:**
  - At concurrency $\le 10$, `maxPendingThreads` is 0 because the pool of 10 connections satisfies all concurrent workers.
  - At concurrency 20, exactly 10 threads are served concurrently while 10 queue in Hikari (`maxPendingThreads = 10`).
  - At concurrency 50, 40 threads queue in Hikari, with latencies scaling proportionally with queue wait times.

---

## 8. Workload B — Hot Account Contention Observations
- **Database Lock Contention Ceiling:** Under hot-account contention (all transfers targeting a single shared account), throughput is capped at ~42–52 TPS across all configurations.
- **Lock Waiter Partitioning:** In every pool configuration, `maxDatabaseLockWaiters` (transactions observed waiting on PostgreSQL locks) reached up to `poolSize - 1`:
  - Pool 5: 4 lock waiters in PostgreSQL, 15/45 queued in Hikari.
  - Pool 10: 9 lock waiters in PostgreSQL, 10/40 queued in Hikari.
  - Pool 15: 14 lock waiters in PostgreSQL, 5/35 queued in Hikari.
  - Pool 20: 19 lock waiters in PostgreSQL, 4/30 queued in Hikari.
- **High-Pool Degradation:** Increasing pool size under heavy account contention (e.g. Concurrency 50) degrades tail latency (p95 increases from 1027.10 ms at Pool 10 to 1281.61 ms at Pool 15 and 1364.65 ms at Pool 20) because up to 19 concurrent PostgreSQL backend processes compete for database locks and wait inside PostgreSQL rather than buffering cleanly in Java memory.

---

## 9. Workload C — Opposing Transfers Observations
- **Deadlock Resilience:** Zero PostgreSQL deadlocks were observed across the tested opposing-transfer repetitions at concurrency 10, 20, and 50 (`deadlocksDelta = 0`). This is consistent with the deterministic account lock ordering design (`ORDER BY s.ledgerAccountId ASC` in application queries and `ORDER BY je.ledger_account_id ASC` in database triggers). This benchmark provides empirical evidence under the tested conditions, not a mathematical proof that no future workload can deadlock.
- **Throughput & Serialization:** Throughput remained steady at ~43–55 TPS, governed by the two shared accounts.

---

## 10. HikariCP Pool Sizing Evaluation (Apples-to-Apples Analysis)

Based strictly on Section 6 (the controlled `pool-comparison` benchmark):

### Low Contention (Disjoint Accounts)
- **Pool 5:** Bottlenecked by connection starvation. Concurrency 20 throughput reached only 65.34 TPS (median p50: 245.81 ms), well below larger pools.
- **Pool 10:** Delivers 81.92 TPS at concurrency 20 and 127.54 TPS at concurrency 50 with stable tail latencies (p95: 499.53 ms / 595.46 ms).
- **Pool 15:** Delivers comparable throughput (76.74 TPS at conc 20, 128.66 TPS at conc 50) without meaningful advantage over Pool 10.
- **Pool 20:** Shows higher low-contention peak throughput (98.71 TPS at conc 20, 156.45 TPS at conc 50) in the synthetic fully-disjoint workload.

### Hot Account Contention (Shared Destination)
- **Pool 10 achieved the best tail latency under heavy contention:** At Concurrency 50, Pool 10 achieved 50.16 TPS with p95 of 1027.10 ms.
- Pool 5 achieved 42.32 TPS with p95 of 1224.20 ms.
- Pool 15 achieved 42.22 TPS with p95 of 1281.61 ms.
- Pool 20 achieved 43.72 TPS with p95 of 1364.65 ms.
- **PostgreSQL Lock Pressure:** Larger pools allowed more transactions to wait concurrently inside PostgreSQL. In the HOT_ACCOUNT workload this coincided with worse p95 tail latency at pools 15 and 20 compared with pool 10 (e.g., at concurrency 50, p95 was 1027.10 ms at pool 10 vs. 1281.61 ms at pool 15 and 1364.65 ms at pool 20, an elevation of ~33%).

---

## 11. Connection Pool Contention vs. Database Lock Contention
The benchmark results cleanly disentangle the three distinct bottleneck types:
1. **Thread / Pool Saturation:** Observed in Workload A at concurrency 20 and 50. Slowdown was predominantly due to waiting for an available Hikari connection in Java memory (`maxPendingThreads` increased to 10 and 40).
2. **Database Lock Contention:** Observed in Workload B. `maxDatabaseLockWaiters` reached up to `poolSize - 1` (4 for Pool 5, 9 for Pool 10, 14 for Pool 15, 19 for Pool 20). Throughput was capped at ~42–52 TPS by shared-account serialization, not connection pool size.
3. **Harmful Pool Inflation:** Expanding the pool beyond 10 under shared-account contention allowed more transactions to wait concurrently inside PostgreSQL (lock waiters reached up to 14 for Pool 15 and 19 for Pool 20) and coincided with elevated p95 tail latency (from 1027.10 ms to 1364.65 ms).

---

## 12. Financial Integrity Verification
Across all 8,100 successful measured transfer operations across the complete official Phase 39 benchmark suite (3,300 measured operations in the Pool-10 baseline scaling experiment and 4,800 measured operations in the controlled Hikari pool-comparison experiment):
- **Journal Structure:** 100% valid (every posted transaction had $\ge 2$ entries, at least one debit, at least one credit, strictly positive amounts).
- **Debit/Credit Equality:** Global sum of debits strictly equaled credits in every run.
- **Snapshot Reconstruction:** Every account balance snapshot matched its exact journal reconstruction.
- **Conservation of Money:** Total money across participant sets was strictly conserved without a single lost or duplicated paise.
- **Failures:** Exactly **0** transaction errors, **0** connection timeouts, and **0** deadlocks across all repetitions.

---

## 13. Final HikariCP Pool Sizing Decision
- **Decision:** **Retain `maximum-pool-size = 10` as the production default.**
- **Justification:**
  Pool size 10 is retained as the production default because it provided the best balanced behavior in this benchmark environment. Larger pools improved the synthetic fully-disjoint workload, but did not provide a consistent advantage and increased PostgreSQL lock pressure under contended financial workloads.
- **Production Status:** **Production configuration unchanged.** `application.yml` retains `maximum-pool-size: 10`.

---

## 14. Limitations & Assumptions
- **Local Developer Hardware Environment:** All measurements were conducted on a single host machine (Windows 11, amd64, 8 logical CPUs, Oracle Java 21.0.2, Docker Engine with Alpine Linux PostgreSQL 17.11). Virtualized container scheduling and OS thread scheduling may introduce transient latency jitter.
- **Direct Service Layer Boundary:** These benchmarks measure execution through Spring `@Transactional` services, Hibernate, HikariCP, and PostgreSQL. They do not incorporate Tomcat HTTP worker thread pools, servlet filters, JSON serialization, TLS termination, network hops, or Nginx reverse proxy overhead.
- **Not an HTTP/Tomcat Capacity Benchmark:** This harness evaluates data-tier connection and lock dynamics rather than end-to-end web ingress throughput or Bucket4j rate limiting.
- **Synthetic Workload Topology:** Real-world transaction traffic exhibits mixed, skewed distributions rather than pure isolated 100% hot-account or 100% disjoint workloads.
- **Single-Node PostgreSQL Architecture:** Benchmarks assume a single primary PostgreSQL node without read replicas, pooling proxies (e.g., PgBouncer), or distributed database topologies.
- **No Production SLA Inference:** These measurements establish comparative architectural baselines on a single developer machine; they do not define production Service Level Agreements (SLAs).
- **No Statistical Significance or Cross-Machine Generality Claims:** Variations in hardware architecture, CPU core counts, storage I/O subsystems, and cloud virtualization environments will influence absolute numbers. Findings reflect relative behavioral patterns under controlled benchmark parameters.

---

## 15. Reproduction Guide

### Windows (PowerShell)
To execute the complete benchmark suite across all 4 pool configurations:
```powershell
powershell -ExecutionPolicy Bypass -File scripts/benchmark-concurrency.ps1
```

### Linux / macOS (Bash)
```bash
./scripts/benchmark-concurrency.sh
```

### Manual Smoke Benchmark
To run a fast development smoke validation (2 workers, 10 operations, pool size 10):
```powershell
.\mvnw.cmd -B -ntp -pl backend/ledgerguard-api -am install -DskipTests
.\mvnw.cmd -B -ntp -f backend/failure-lab/pom.xml test -Pperformance-benchmark "-Dbenchmark.pool.size=10" "-Dbenchmark.mode=smoke"
```

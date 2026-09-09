#!/usr/bin/env bash
# Bash script to execute Phase 39 Concurrency & Pool Benchmarks
set -euo pipefail

echo "============================================================"
echo "LEDGERGUARD PHASE 39 CONCURRENCY & POOL BENCHMARK RUNNER"
echo "============================================================"

# 1. Verify Docker is available
echo "[1/8] Verifying Docker daemon..."
if ! docker info >/dev/null 2>&1; then
    echo "ERROR: Docker daemon is not running or docker command failed." >&2
    exit 1
fi
echo "Docker daemon is available."

START_TIME=$(date +%s)

# 2. Prepare API dependency once
echo "[2/8] Preparing API dependency (-pl backend/ledgerguard-api -am install -DskipTests)..."
./mvnw -B -ntp -pl backend/ledgerguard-api -am install -DskipTests

# 3. Remove old target/phase39-benchmarks directory if it exists
BENCHMARK_DIR="backend/failure-lab/target/phase39-benchmarks"
if [ -d "$BENCHMARK_DIR" ]; then
    echo "[3/8] Cleaning existing benchmark output directory: $BENCHMARK_DIR"
    rm -rf "$BENCHMARK_DIR"
else
    echo "[3/8] Output directory $BENCHMARK_DIR does not exist yet."
fi

# 4. Run Pool 10 Baseline
echo "[4/8] Running Pool Size 10 (BASELINE: Low Contention, Hot Account, Opposing Transfers)..."
./mvnw -B -ntp -f backend/failure-lab/pom.xml test \
  -Pperformance-benchmark \
  -Dbenchmark.pool.size=10 \
  -Dbenchmark.mode=baseline

# 5. Run Pool 5 Comparison
echo "[5/8] Running Pool Size 5 (POOL-COMPARISON)..."
./mvnw -B -ntp -f backend/failure-lab/pom.xml test \
  -Pperformance-benchmark \
  -Dbenchmark.pool.size=5 \
  -Dbenchmark.mode=pool-comparison

# 6. Run Pool 10 Comparison (Fair Comparison Baseline)
echo "[6/8] Running Pool Size 10 (POOL-COMPARISON)..."
./mvnw -B -ntp -f backend/failure-lab/pom.xml test \
  -Pperformance-benchmark \
  -Dbenchmark.pool.size=10 \
  -Dbenchmark.mode=pool-comparison

# 7. Run Pool 15 Comparison
echo "[7/8] Running Pool Size 15 (POOL-COMPARISON)..."
./mvnw -B -ntp -f backend/failure-lab/pom.xml test \
  -Pperformance-benchmark \
  -Dbenchmark.pool.size=15 \
  -Dbenchmark.mode=pool-comparison

# 8. Run Pool 20 Comparison
echo "[8/8] Running Pool Size 20 (POOL-COMPARISON)..."
./mvnw -B -ntp -f backend/failure-lab/pom.xml test \
  -Pperformance-benchmark \
  -Dbenchmark.pool.size=20 \
  -Dbenchmark.mode=pool-comparison

END_TIME=$(date +%s)
TOTAL_ELAPSED=$(( END_TIME - START_TIME ))

echo "============================================================"
echo "PHASE 39 BENCHMARKS COMPLETE"
echo "Total Elapsed Time: ${TOTAL_ELAPSED} seconds"
echo "Results Directory:  $BENCHMARK_DIR"
echo "Generated Result Files:"
ls -lh "$BENCHMARK_DIR"
echo "============================================================"

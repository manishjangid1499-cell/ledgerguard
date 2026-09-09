# PowerShell script to execute Phase 39 Concurrency & Pool Benchmarks
$ErrorActionPreference = "Stop"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "LEDGERGUARD PHASE 39 CONCURRENCY & POOL BENCHMARK RUNNER" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# 1. Verify Docker is available
Write-Host "[1/8] Verifying Docker daemon..." -ForegroundColor Yellow
try {
    docker info > $null 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Docker daemon is not running. Please start Docker Desktop and retry."
        exit 1
    }
} catch {
    Write-Error "Docker command failed or Docker is not installed."
    exit 1
}
Write-Host "Docker daemon is available." -ForegroundColor Green

$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

# 2. Prepare API dependency once
Write-Host "[2/8] Preparing API dependency (-pl backend/ledgerguard-api -am install -DskipTests)..." -ForegroundColor Yellow
& .\mvnw.cmd -B -ntp -pl backend/ledgerguard-api -am install -DskipTests
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to compile/install backend/ledgerguard-api dependency."
    exit $LASTEXITCODE
}
Write-Host "API dependency prepared successfully." -ForegroundColor Green

# 3. Remove old target/phase39-benchmarks directory if it exists
$benchmarkDir = "backend/failure-lab/target/phase39-benchmarks"
if (Test-Path $benchmarkDir) {
    Write-Host "[3/8] Cleaning existing benchmark output directory: $benchmarkDir" -ForegroundColor Yellow
    Remove-Item -Recurse -Force $benchmarkDir
} else {
    Write-Host "[3/8] Output directory $benchmarkDir does not exist yet." -ForegroundColor Yellow
}

# 4. Run Pool 10 Baseline
Write-Host "[4/8] Running Pool Size 10 (BASELINE: Low Contention, Hot Account, Opposing Transfers)..." -ForegroundColor Yellow
& .\mvnw.cmd -B -ntp -f backend/failure-lab/pom.xml test `
  -Pperformance-benchmark `
  "-Dbenchmark.pool.size=10" `
  "-Dbenchmark.mode=baseline"
if ($LASTEXITCODE -ne 0) {
    Write-Error "Pool 10 baseline benchmark failed."
    exit $LASTEXITCODE
}
Write-Host "Pool 10 baseline completed successfully." -ForegroundColor Green

# 5. Run Pool 5 Comparison
Write-Host "[5/8] Running Pool Size 5 (POOL-COMPARISON: Low Contention, Hot Account)..." -ForegroundColor Yellow
& .\mvnw.cmd -B -ntp -f backend/failure-lab/pom.xml test `
  -Pperformance-benchmark `
  "-Dbenchmark.pool.size=5" `
  "-Dbenchmark.mode=pool-comparison"
if ($LASTEXITCODE -ne 0) {
    Write-Error "Pool 5 comparison benchmark failed."
    exit $LASTEXITCODE
}
Write-Host "Pool 5 comparison completed successfully." -ForegroundColor Green

# 6. Run Pool 10 Comparison (Fair Comparison Baseline)
Write-Host "[6/8] Running Pool Size 10 (POOL-COMPARISON: Low Contention, Hot Account)..." -ForegroundColor Yellow
& .\mvnw.cmd -B -ntp -f backend/failure-lab/pom.xml test `
  -Pperformance-benchmark `
  "-Dbenchmark.pool.size=10" `
  "-Dbenchmark.mode=pool-comparison"
if ($LASTEXITCODE -ne 0) {
    Write-Error "Pool 10 comparison benchmark failed."
    exit $LASTEXITCODE
}
Write-Host "Pool 10 comparison completed successfully." -ForegroundColor Green

# 7. Run Pool 15 Comparison
Write-Host "[7/8] Running Pool Size 15 (POOL-COMPARISON: Low Contention, Hot Account)..." -ForegroundColor Yellow
& .\mvnw.cmd -B -ntp -f backend/failure-lab/pom.xml test `
  -Pperformance-benchmark `
  "-Dbenchmark.pool.size=15" `
  "-Dbenchmark.mode=pool-comparison"
if ($LASTEXITCODE -ne 0) {
    Write-Error "Pool 15 comparison benchmark failed."
    exit $LASTEXITCODE
}
Write-Host "Pool 15 comparison completed successfully." -ForegroundColor Green

# 8. Run Pool 20 Comparison
Write-Host "[8/8] Running Pool Size 20 (POOL-COMPARISON: Low Contention, Hot Account)..." -ForegroundColor Yellow
& .\mvnw.cmd -B -ntp -f backend/failure-lab/pom.xml test `
  -Pperformance-benchmark `
  "-Dbenchmark.pool.size=20" `
  "-Dbenchmark.mode=pool-comparison"
if ($LASTEXITCODE -ne 0) {
    Write-Error "Pool 20 comparison benchmark failed."
    exit $LASTEXITCODE
}
Write-Host "Pool 20 comparison completed successfully." -ForegroundColor Green

$stopwatch.Stop()
$totalElapsedSeconds = [math]::Round($stopwatch.Elapsed.TotalSeconds, 2)
$totalElapsedMinutes = [math]::Round($stopwatch.Elapsed.TotalMinutes, 2)

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "PHASE 39 BENCHMARKS COMPLETE" -ForegroundColor Cyan
Write-Host "Total Elapsed Time: $totalElapsedSeconds seconds ($totalElapsedMinutes minutes)" -ForegroundColor Green
Write-Host "Results Directory:  $benchmarkDir" -ForegroundColor Green
Write-Host "Generated Result Files:" -ForegroundColor Cyan
Get-ChildItem -Path $benchmarkDir | ForEach-Object {
    Write-Host "  - $($_.Name) ($($_.Length) bytes)" -ForegroundColor White
}
Write-Host "============================================================" -ForegroundColor Cyan

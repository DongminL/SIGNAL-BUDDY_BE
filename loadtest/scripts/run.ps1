# Run a load-test scenario against either the single or sentinel Redis topology.
# Non-failover scenarios use staged execution with DB cleanup between each stage.
#
# Usage:
#   .\scripts\run.ps1 -Scenario like-only    -Topology single
#   .\scripts\run.ps1 -Scenario failover     -Topology sentinel
param(
  [ValidateSet('like-only','mixed','eviction-ramp','failover')]
  [string]$Scenario = $(throw "Provide -Scenario"),
  [ValidateSet('single','sentinel')]
  [string]$Topology = $(throw "Provide -Topology")
)

$ErrorActionPreference = 'Stop'

$scriptDir   = Split-Path $MyInvocation.MyCommand.Path -Parent
$loadtestDir = Split-Path $scriptDir -Parent
$dockerDir   = Join-Path $loadtestDir '..\submodule\docker'
$composeFile = Join-Path $dockerDir "docker-compose.redis-$Topology.yml"
$redisPort   = 6379
$redisPass   = 'ajdLj55fld!!sj'
$dbHost      = if ($env:DB_HOST) { $env:DB_HOST } else { '127.0.0.1' }
$dbUser      = if ($env:DB_USER) { $env:DB_USER } else { 'cklol' }
$dbPass      = if ($env:DB_PASS) { $env:DB_PASS } else { 'a67b94dd86b9f0ddc6dc11dd0b9c878047d5266785fdd0ce3ee2a7ee932fb7cc' }
$dbName      = if ($env:DB_NAME) { $env:DB_NAME } else { 'signal-buddy' }
$baseUrl     = if ($env:BASE_URL) { $env:BASE_URL } else { 'http://localhost:8080' }
$ts          = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$resultDir   = Join-Path $loadtestDir "results\$ts-$Scenario-$Topology"

# Redis container name differs by topology
$redisContainer = if ($Topology -eq 'sentinel') { 'redis-master' } else { 'redis-single' }

if ($Scenario -eq 'failover' -and $Topology -ne 'sentinel') {
  throw "failover scenario requires sentinel topology"
}

function Invoke-RedisCli {
  param([string[]]$CliArgs)
  if (Get-Command redis-cli -ErrorAction SilentlyContinue) {
    & redis-cli -p $redisPort -a $redisPass --no-auth-warning @CliArgs 2>$null
  } else {
    & docker exec $redisContainer redis-cli -a $redisPass --no-auth-warning @CliArgs 2>$null
  }
}

function Get-Stages([string]$scenario) {
  switch ($scenario) {
    'like-only'     { return @('200:2m','500:3m','1000:3m','1500:3m') }
    'mixed'         { return @('200:2m','600:3m','1000:3m','1200:3m') }
    'eviction-ramp' { return @('300:2m','800:3m','1500:3m','2500:3m','3000:4m') }
    default         { throw "Unknown scenario: $scenario" }
  }
}

function Invoke-CleanupLikes([string]$label = '') {
  $suffix = if ($label) { " $label" } else { '' }
  Write-Host "==> [cleanup$suffix] Deleting Redis keys (like:pending, like:processing)..."
  Invoke-RedisCli 'del', 'like:pending', 'like:processing' | Out-Null

  Write-Host "==> [cleanup$suffix] Waiting for batch job (12s)..."
  Start-Sleep -Seconds 12

  Write-Host "==> [cleanup$suffix] Resetting DB likes (cleanup-likes.sql)..."
  $sqlFile = Join-Path $loadtestDir 'sql\cleanup-likes.sql'
  Get-Content $sqlFile | & mysql -h $dbHost -u $dbUser "-p$dbPass" $dbName

  Write-Host "==> [cleanup$suffix] Done."
}

# 1. Start Redis (skip if already responding)
$ping = Invoke-RedisCli 'ping'
if ($ping -match 'PONG') {
  Write-Host "==> Redis already running on port $redisPort -- skipping docker compose up."
} else {
  Write-Host "==> Starting $Topology Redis..."
  & docker compose -f $composeFile up -d
  Write-Host "Waiting for Redis to be ready..."
  while ($true) {
    $ping = Invoke-RedisCli 'ping'
    if ($ping -match 'PONG') { break }
    Start-Sleep -Seconds 1
  }
  Write-Host "Redis is ready."
}

# 2. Build k6 bundles
Write-Host "==> Building k6 bundles..."
Set-Location $loadtestDir
npm run build

# 3. Prepare results directory
New-Item -ItemType Directory -Path $resultDir -Force | Out-Null

if ($Scenario -eq 'failover') {
  $samplerJob = Start-Job -ScriptBlock {
    param($script, $out, $port)
    & pwsh -NonInteractive -File $script -Out $out -Port $port
  } -ArgumentList "$scriptDir\sample-evictions.ps1", "$resultDir\evictions.csv", $redisPort
  Write-Host "Eviction sampler started (job $($samplerJob.Id))"

  $failoverJob = Start-Job -ScriptBlock {
    Start-Sleep -Seconds 120
    Write-Host "==> [failover] Stopping redis-master..."
    docker stop redis-master
  }

  Write-Host "==> Running k6 scenario: failover..."
  & k6 run `
    "--env" "BASE_URL=$baseUrl" `
    "--summary-export=$resultDir\summary.json" `
    "--out" "json=$resultDir\raw.json" `
    "dist\failover.js"

  Stop-Job $samplerJob  -ErrorAction SilentlyContinue
  Remove-Job $samplerJob -ErrorAction SilentlyContinue
  Stop-Job $failoverJob  -ErrorAction SilentlyContinue
  Remove-Job $failoverJob -ErrorAction SilentlyContinue

  Invoke-CleanupLikes
} else {
  $stages = Get-Stages $Scenario
  $total  = $stages.Count

  Write-Host ""
  Write-Host "============================================"
  Write-Host "  Scenario : $Scenario"
  Write-Host "  Topology : $Topology"
  Write-Host "  Stages   : $total"
  Write-Host "============================================"

  for ($i = 0; $i -lt $stages.Count; $i++) {
    $parts      = $stages[$i] -split ':'
    $targetRps  = $parts[0]
    $duration   = $parts[1]
    $stageNum   = $i + 1
    $stageLabel = "stage${stageNum}-${targetRps}rps"
    $stageDir   = Join-Path $resultDir $stageLabel
    New-Item -ItemType Directory -Path $stageDir -Force | Out-Null

    Write-Host ""
    Write-Host "  --> [Stage $stageNum/$total] $targetRps RPS x $duration"

    $samplerJob = Start-Job -ScriptBlock {
      param($script, $out, $port)
      & pwsh -NonInteractive -File $script -Out $out -Port $port
    } -ArgumentList "$scriptDir\sample-evictions.ps1", "$stageDir\evictions.csv", $redisPort
    Write-Host "      Eviction sampler started (job $($samplerJob.Id))"

    & k6 run `
      "--env" "TARGET_RPS=$targetRps" `
      "--env" "DURATION=$duration" `
      "--env" "BASE_URL=$baseUrl" `
      "--summary-export=$stageDir\summary.json" `
      "--out" "json=$stageDir\raw.json" `
      "dist\$Scenario-stage.js"
    $k6Exit = $LASTEXITCODE

    Stop-Job $samplerJob  -ErrorAction SilentlyContinue
    Remove-Job $samplerJob -ErrorAction SilentlyContinue

    Write-Host "  --> [Stage $stageNum/$total] k6 done (exit=$k6Exit) -- cleanup likes..."
    Invoke-CleanupLikes $stageLabel
  }

  Write-Host ""
  Write-Host "  [$Scenario] All stages complete."
}

Write-Host "==> Results saved to: $resultDir"
Write-Host "==> Docker containers left running. Stop with:"
Write-Host "    docker compose -f $composeFile down"

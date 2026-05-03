# Samples Redis eviction metrics every second and writes to CSV.
# Usage: .\scripts\sample-evictions.ps1 [-Out results\evictions.csv] [-Port 6379]
param(
  [string]$Out  = "results\evictions.csv",
  [int]   $Port = 6379
)

$pass = "ajdLj55fld!!sj"
$dir  = Split-Path $Out -Parent
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }

"ts,evicted_keys,used_memory_human,maxmemory_human" | Set-Content $Out -Encoding utf8
Write-Host "Sampling Redis :$Port -> $Out  (Ctrl-C to stop)"

while ($true) {
  $info = & redis-cli -p $Port -a $pass --no-auth-warning INFO stats INFO memory 2>$null
  $evicted = ($info | Select-String '^evicted_keys:(\S+)').Matches.Groups[1].Value
  $used    = ($info | Select-String '^used_memory_human:(\S+)').Matches.Groups[1].Value
  $maxmem  = ($info | Select-String '^maxmemory_human:(\S+)').Matches.Groups[1].Value
  $ts      = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
  "$ts,$($evicted ?? 0),$($used ?? '?'),$($maxmem ?? '?')" | Add-Content $Out -Encoding utf8
  Start-Sleep -Seconds 1
}

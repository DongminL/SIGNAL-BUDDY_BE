#!/usr/bin/env bash
# Samples Redis eviction metrics every second and writes to CSV.
# Usage: ./scripts/sample-evictions.sh <output.csv> [redis-port]
# Runs until SIGTERM/SIGINT is received.

set -euo pipefail

OUT="${1:-results/evictions.csv}"
PORT="${2:-6379}"
PASS="ajdLj55fld!!sj"

mkdir -p "$(dirname "$OUT")"
echo "ts,evicted_keys,used_memory_human,maxmemory_human" > "$OUT"

echo "Sampling Redis :${PORT} → ${OUT}  (Ctrl-C to stop)"

while true; do
  info=$(redis-cli -p "$PORT" -a "$PASS" --no-auth-warning INFO all 2>/dev/null) || true
  evicted=$(echo "$info" | grep '^evicted_keys:' | tr -d '\r' | cut -d: -f2)
  used=$(echo "$info"    | grep '^used_memory_human:' | tr -d '\r' | cut -d: -f2)
  maxmem=$(echo "$info"  | grep '^maxmemory_human:' | tr -d '\r' | cut -d: -f2)
  ts=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  echo "${ts},${evicted:-0},${used:-?},${maxmem:-?}" >> "$OUT"
  sleep 1
done

#!/usr/bin/env bash
# Run a load-test scenario against either the single or sentinel Redis topology.
# Non-failover scenarios use staged execution with DB cleanup between each stage.
#
# Usage:
#   ./scripts/run.sh <scenario> <topology>
#   scenario : like-only | mixed | eviction-ramp | failover
#   topology : single | sentinel
#
# Examples:
#   ./scripts/run.sh like-only single
#   ./scripts/run.sh failover  sentinel

set -euo pipefail

SCENARIO="${1:?Usage: run.sh <scenario> <topology>}"
TOPOLOGY="${2:?Usage: run.sh <scenario> <topology>}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOADTEST_DIR="$(dirname "$SCRIPT_DIR")"
DOCKER_DIR="$LOADTEST_DIR/../submodule/docker"
COMPOSE_FILE="$DOCKER_DIR/docker-compose.redis-${TOPOLOGY}.yml"
REDIS_PORT=6379
REDIS_PASS="ajdLj55fld!!sj"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_USER="${DB_USER:-cklol}"
DB_PASS="${DB_PASS:-a67b94dd86b9f0ddc6dc11dd0b9c878047d5266785fdd0ce3ee2a7ee932fb7cc}"
DB_NAME="${DB_NAME:-signal-buddy}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
TS=$(date -u +"%Y%m%dT%H%M%SZ")
RESULT_DIR="$LOADTEST_DIR/results/${TS}-${SCENARIO}-${TOPOLOGY}"

if [[ "$SCENARIO" == "failover" && "$TOPOLOGY" != "sentinel" ]]; then
  echo "ERROR: failover scenario requires sentinel topology"
  exit 1
fi

get_stages() {
  case "$1" in
    like-only)     echo "200:2m 500:3m 1000:3m 1500:3m" ;;
    mixed)         echo "200:2m 600:3m 1000:3m 1200:3m" ;;
    eviction-ramp) echo "300:2m 800:3m 1500:3m 2500:3m 3000:4m" ;;
    *) echo "ERROR: 알 수 없는 시나리오 '$1'" >&2; exit 1 ;;
  esac
}

cleanup_likes() {
  local label="${1:-}"
  echo "==> [cleanup${label:+ $label}] Redis 키 삭제 (like:pending, like:processing)..."
  redis-cli -p "$REDIS_PORT" -a "$REDIS_PASS" --no-auth-warning \
    del like:pending like:processing > /dev/null 2>&1 || true

  echo "==> [cleanup${label:+ $label}] 배치 잡 완료 대기 (12s)..."
  sleep 12

  echo "==> [cleanup${label:+ $label}] DB likes 초기화 (cleanup-likes.sql)..."
  mysql -h "$DB_HOST" -u "$DB_USER" -p"$DB_PASS" "$DB_NAME" \
    < "$LOADTEST_DIR/sql/cleanup-likes.sql"

  echo "==> [cleanup${label:+ $label}] 초기화 완료"
}

# 1. Start Redis
echo "==> Starting ${TOPOLOGY} Redis..."
docker compose -f "$COMPOSE_FILE" up -d
echo "Waiting for Redis to be ready..."
until redis-cli -p "$REDIS_PORT" -a "$REDIS_PASS" --no-auth-warning ping 2>/dev/null | grep -q PONG; do
  sleep 1
done
echo "Redis is ready."

# 2. Build k6 bundles
echo "==> Building k6 bundles..."
cd "$LOADTEST_DIR"
npm run build

# 3. Prepare results dir
mkdir -p "$RESULT_DIR"

if [[ "$SCENARIO" == "failover" ]]; then
  # failover: 단일 실행 — 스테이지는 sentinel 페일오버 관찰 흐름이므로 분리하지 않음
  bash "$SCRIPT_DIR/sample-evictions.sh" "$RESULT_DIR/evictions.csv" "$REDIS_PORT" &
  SAMPLER_PID=$!
  echo "Eviction sampler started (PID $SAMPLER_PID)"

  (
    sleep 120
    echo "==> [failover] Stopping redis-master..."
    docker stop redis-master || true
  ) &

  echo "==> Running k6 scenario: failover..."
  set +e
  k6 run \
    --env BASE_URL="$BASE_URL" \
    --summary-export="$RESULT_DIR/summary.json" \
    --out "json=$RESULT_DIR/raw.json" \
    "dist/failover.js"
  k6_exit=$?
  set -e

  kill "$SAMPLER_PID" 2>/dev/null || true
  wait "$SAMPLER_PID" 2>/dev/null || true

  cleanup_likes
else
  # 스테이지 기반 실행: 각 스테이지 종료 후 DB 초기화
  read -ra stages <<< "$(get_stages "$SCENARIO")"
  total=${#stages[@]}

  echo ""
  echo "============================================"
  echo "  시나리오 : $SCENARIO"
  echo "  토폴로지 : $TOPOLOGY"
  echo "  스테이지 : $total 개"
  echo "============================================"

  for i in "${!stages[@]}"; do
    IFS=':' read -r target_rps duration <<< "${stages[$i]}"
    stage_num=$((i + 1))
    stage_label="stage${stage_num}-${target_rps}rps"
    stage_dir="$RESULT_DIR/$stage_label"
    mkdir -p "$stage_dir"

    echo ""
    echo "  --> [Stage ${stage_num}/${total}] ${target_rps} RPS × ${duration}"

    bash "$SCRIPT_DIR/sample-evictions.sh" "$stage_dir/evictions.csv" "$REDIS_PORT" &
    SAMPLER_PID=$!
    echo "      eviction 샘플러 시작 (PID $SAMPLER_PID)"

    set +e
    k6 run \
      --env TARGET_RPS="$target_rps" \
      --env DURATION="$duration" \
      --env BASE_URL="$BASE_URL" \
      "--summary-export=$stage_dir/summary.json" \
      "--out" "json=$stage_dir/raw.json" \
      "dist/${SCENARIO}-stage.js"
    k6_exit=$?
    set -e

    kill "$SAMPLER_PID" 2>/dev/null || true
    wait "$SAMPLER_PID" 2>/dev/null || true

    echo "  --> [Stage ${stage_num}/${total}] k6 완료 (exit=$k6_exit) — 좋아요 초기화..."
    cleanup_likes "$stage_label"
  done

  echo ""
  echo "  [$SCENARIO] 전체 스테이지 완료"
fi

echo "==> Results saved to: $RESULT_DIR"
echo "==> Docker containers left running. Stop with:"
echo "    docker compose -f $COMPOSE_FILE down"

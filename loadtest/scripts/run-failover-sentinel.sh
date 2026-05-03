#!/usr/bin/env bash
# run-failover-sentinel.sh — Sentinel failover 부하 테스트
# 사용법:
#   ./scripts/run-failover-sentinel.sh            # 단일 실행 (failover.js, ~15분)
#   ./scripts/run-failover-sentinel.sh --staged   # 스테이지 실행 (failover-stage.js, 100/200/400 RPS × 11min)
set -eo pipefail

STAGED=false
if [[ "${1:-}" == "--staged" ]]; then
  STAGED=true
fi

LOADTEST_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SENTINEL_CONTAINER="redis-sentinel-1"
SENTINEL_PORT=26379
MASTER_CONTAINER="redis-master"
REDIS_PORT=6379
REDIS_PASS='ajdLj55fld!!sj'
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_USER="${DB_USER:-cklol}"
DB_PASS="${DB_PASS:-a67b94dd86b9f0ddc6dc11dd0b9c878047d5266785fdd0ce3ee2a7ee932fb7cc}"
DB_NAME="${DB_NAME:-signal-buddy}"
BASE_URL="${BASE_URL:-http://localhost:8080}"

# 단일 모드: graceful-stop + @Retryable 재시도 체인 고려해 1080s로 force-kill
K6_FORCE_KILL_AFTER_SINGLE=1080
# staged 모드: 11분 스테이지 + graceful-stop 고려해 800s로 force-kill
K6_FORCE_KILL_AFTER_STAGED=800

STAGED_STAGES="100 200 400"

cd "$LOADTEST_DIR"

# ---------------------------------------------------------------------------
# 헬퍼 함수
# ---------------------------------------------------------------------------

get_master_port() {
  docker exec "$SENTINEL_CONTAINER" redis-cli -p "$SENTINEL_PORT" \
    sentinel get-master-addr-by-name mymaster 2>/dev/null | sed -n '2p' | tr -d '\r '
}

port_to_container() {
  case "$1" in
    6379) echo "redis-master" ;;
    6380) echo "redis-replica-1" ;;
    6381) echo "redis-replica-2" ;;
    *)    echo "" ;;
  esac
}

get_master_container() {
  local port; port=$(get_master_port)
  local c; c=$(port_to_container "$port")
  echo "${c:-redis-master}"
}

cleanup_likes() {
  local label="${1:-}"
  local mc; mc=$(get_master_container)

  echo "    [cleanup${label:+ $label}] Redis 키 삭제 (like:pending, like:processing) — master: $mc"
  docker exec "$mc" redis-cli -p "$REDIS_PORT" \
    -a "$REDIS_PASS" --no-auth-warning \
    del like:pending like:processing > /dev/null 2>&1 || true

  echo "    [cleanup${label:+ $label}] 배치 잡 완료 대기 (12s)..."
  sleep 12

  echo "    [cleanup${label:+ $label}] DB likes 초기화 (cleanup-likes.sql)..."
  mysql -h "$DB_HOST" -u "$DB_USER" -p"$DB_PASS" "$DB_NAME" \
    < "$LOADTEST_DIR/sql/cleanup-likes.sql"

  echo "    [cleanup${label:+ $label}] 초기화 완료"
}

# ---------------------------------------------------------------------------
# 사전 점검 (공통)
# ---------------------------------------------------------------------------
mkdir -p results

echo "==> k6 번들 빌드..."
npm run build

echo "==> Sentinel 컨테이너($SENTINEL_CONTAINER) 상태 확인..."
if ! docker exec "$SENTINEL_CONTAINER" redis-cli -p "$SENTINEL_PORT" \
    ping 2>/dev/null | grep -q PONG; then
  echo "ERROR: $SENTINEL_CONTAINER 컨테이너가 실행 중이지 않거나 응답하지 않습니다."
  exit 1
fi

if ! docker inspect --format '{{.State.Status}}' "$MASTER_CONTAINER" 2>/dev/null | grep -q "running"; then
  echo "ERROR: redis-master 컨테이너가 실행 중이 아닙니다. 먼저 기동하세요."
  exit 1
fi

master_info=$(docker exec "$SENTINEL_CONTAINER" redis-cli -p "$SENTINEL_PORT" \
  sentinel get-master-addr-by-name mymaster 2>/dev/null | tr '\n' ':' | sed 's/:$//')
echo "현재 Sentinel master: $master_info"
echo "Sentinel is ready."

# ---------------------------------------------------------------------------
# 단일 failover 스테이지 실행 함수 (staged 모드 내부에서 반복 호출)
# ---------------------------------------------------------------------------
run_failover_stage() {
  local target_rps="$1"
  local result_dir="$2"
  local force_kill_after="$3"
  local k6_script="$4"
  local stage_label="stage-${target_rps}rps"
  local stage_dir="$result_dir/$stage_label"
  mkdir -p "$stage_dir"

  local master_port; master_port=$(get_master_port)
  local master_container; master_container=$(port_to_container "$master_port")
  if [[ -z "$master_container" ]]; then
    echo "  [ERROR] Sentinel에서 master 정보를 가져올 수 없습니다."
    return 1
  fi

  echo ""
  echo "  --> [${stage_label}] ${target_rps} RPS × 11m  |  현재 master: 127.0.0.1:${master_port} ($master_container)"

  # eviction 샘플러
  (
    echo "ts,evicted_keys,used_memory_human,maxmemory_human" > "$stage_dir/evictions.csv"
    while true; do
      local mc_port; mc_port=$(docker exec "$SENTINEL_CONTAINER" redis-cli -p "$SENTINEL_PORT" \
        sentinel get-master-addr-by-name mymaster 2>/dev/null | sed -n '2p' | tr -d '\r ' || echo "$master_port")
      local mc; mc=$(port_to_container "$mc_port")
      mc="${mc:-redis-master}"
      local info; info=$(docker exec "$mc" redis-cli -p "$REDIS_PORT" \
        -a "$REDIS_PASS" --no-auth-warning info all 2>/dev/null) || true
      local evicted; evicted=$(echo "$info" | grep "^evicted_keys:" | awk -F: '{print $2}' | tr -d '\r')
      local used; used=$(echo "$info" | grep "^used_memory_human:" | awk -F: '{print $2}' | tr -d '\r ')
      local maxmem; maxmem=$(echo "$info" | grep "^maxmemory_human:" | awk -F: '{print $2}' | tr -d '\r ')
      local ts_now; ts_now=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
      echo "${ts_now},${evicted:-0},${used:-?},${maxmem:-?}" >> "$stage_dir/evictions.csv"
      sleep 1
    done
  ) &
  local sampler_pid=$!
  echo "      eviction 샘플러 시작 (PID $sampler_pid)"

  # t=120s에 현재 master 강제 종료 타이머
  (
    sleep 120
    echo ""
    echo "  [failover/${stage_label}] t=120s: $master_container (${master_port}) 강제 종료..."
    docker stop "$master_container" > /dev/null 2>&1 || true
    echo "  [failover/${stage_label}] master 종료 완료. Sentinel이 replica를 승격합니다."
    for i in $(seq 1 30); do
      sleep 1
      local new_port; new_port=$(docker exec "$SENTINEL_CONTAINER" redis-cli -p "$SENTINEL_PORT" \
        sentinel get-master-addr-by-name mymaster 2>/dev/null | sed -n '2p' | tr -d '\r ' || echo "")
      if [[ -n "$new_port" && "$new_port" != "$master_port" ]]; then
        local new_container; new_container=$(port_to_container "$new_port")
        echo "  [failover/${stage_label}] t=$((120+i))s: 새 master 선출 → 127.0.0.1:${new_port} ($new_container)"
        break
      fi
      if [[ $i -eq 30 ]]; then
        echo "  [failover/${stage_label}] [WARN] 30s 내 master 교체 미확인"
      fi
    done
  ) &
  local failover_pid=$!

  # k6 실행 (force-kill 감시)
  local k6_env_args=(--env BASE_URL="$BASE_URL")
  if [[ -n "$target_rps" && "$target_rps" != "0" ]]; then
    k6_env_args+=(--env TARGET_RPS="$target_rps")
  fi

  set +e
  k6 run \
    "${k6_env_args[@]}" \
    "--summary-export=$stage_dir/summary.json" \
    "--out" "json=$stage_dir/raw.json" \
    "$k6_script" &
  local k6_pid=$!
  echo "      k6 시작 (PID $k6_pid)"

  local elapsed=0
  local k6_exit=0
  while kill -0 "$k6_pid" 2>/dev/null; do
    sleep 10
    elapsed=$((elapsed + 10))
    if ((elapsed >= force_kill_after)); then
      echo "      [force-kill] k6 ${force_kill_after}s 초과 — 강제 종료"
      kill -9 "$k6_pid" 2>/dev/null || true
      k6_exit=137
      break
    fi
  done
  wait "$k6_pid" 2>/dev/null || true
  if [[ $k6_exit -eq 0 ]]; then k6_exit=$?; fi
  set -e

  kill "$sampler_pid" 2>/dev/null || true
  wait "$sampler_pid" 2>/dev/null || true
  kill "$failover_pid" 2>/dev/null || true
  wait "$failover_pid" 2>/dev/null || true

  echo "  --> [${stage_label}] k6 완료 (exit=$k6_exit)"

  if [[ ! -f "$stage_dir/summary.json" ]]; then
    echo "      summary.json 없음 (force-kill) — raw.json에서 합성..."
    node scripts/analyze-raw.mjs "$stage_dir" || true
  fi

  # 종료된 master 재시작 + replica 재합류 대기
  echo "  --> [${stage_label}] $master_container 재시작..."
  docker start "$master_container" > /dev/null 2>&1 || true

  echo "  --> [${stage_label}] $master_container(${master_port})가 replica로 재합류 대기 (최대 60s)..."
  for i in $(seq 1 60); do
    sleep 1
    local raw; raw=$(docker exec "$SENTINEL_CONTAINER" redis-cli -p "$SENTINEL_PORT" \
      sentinel replicas mymaster 2>/dev/null | tr '\n' ' ' || echo "")
    if echo "$raw" | grep -q "${master_port}"; then
      echo "      $master_container(${master_port}) 재합류 확인 (t=${i}s)"
      break
    fi
    if [[ $i -eq 60 ]]; then
      echo "      [WARN] 60s 내 replica 재합류 미확인"
    fi
  done

  local final_port; final_port=$(get_master_port)
  echo "  --> [${stage_label}] 최종 master: 127.0.0.1:${final_port} ($(port_to_container "$final_port"))"

  cleanup_likes "$stage_label"
}

# ---------------------------------------------------------------------------
# 실행
# ---------------------------------------------------------------------------
SCENARIO_TS=$(date -u +"%Y%m%dT%H%M%SZ")

if [[ "$STAGED" == "true" ]]; then
  RESULT_DIR="results/${SCENARIO_TS}-failover-staged-sentinel"
  mkdir -p "$RESULT_DIR"

  echo ""
  echo "============================================"
  echo "  시나리오 : failover (staged)"
  echo "  토폴로지 : sentinel"
  echo "  결과     : $RESULT_DIR"
  echo "  스테이지 : 100 / 200 / 400 RPS × 11min each"
  echo "============================================"

  for rps in $STAGED_STAGES; do
    run_failover_stage "$rps" "$RESULT_DIR" "$K6_FORCE_KILL_AFTER_STAGED" "dist/failover-stage.js"
  done

  echo ""
  echo "=========================================="
  echo "  Sentinel failover 스테이지 전체 완료"
  echo "  결과: $RESULT_DIR"
  echo "=========================================="
  echo "$RESULT_DIR" >> "$LOADTEST_DIR/results/completed-staged-failover-sentinel.txt"
else
  RESULT_DIR="results/${SCENARIO_TS}-failover-sentinel"
  mkdir -p "$RESULT_DIR"

  echo ""
  echo "============================================"
  echo "  시나리오 : failover"
  echo "  토폴로지 : sentinel"
  echo "  결과     : $RESULT_DIR"
  echo "  k6 force-kill 한도: ${K6_FORCE_KILL_AFTER_SINGLE}s"
  echo "============================================"

  run_failover_stage "0" "$RESULT_DIR" "$K6_FORCE_KILL_AFTER_SINGLE" "dist/failover.js"

  echo ""
  echo "=========================================="
  echo "  failover 부하 테스트 완료"
  echo "  결과: $RESULT_DIR"
  echo "=========================================="
  echo "$RESULT_DIR" >> "$LOADTEST_DIR/results/completed-failover-sentinel.txt"
fi

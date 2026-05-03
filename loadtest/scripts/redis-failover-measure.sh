#!/usr/bin/env bash
# Redis Sentinel Failover 시간 측정 스크립트
# 구성: master(6379) + replica-1(6380) + replica-2(6381) + sentinel x3 (26379~26381)
# quorum=2, down-after-milliseconds=5000, failover-timeout=10000

MASTER_NAME="mymaster"
REDIS_PASSWORD="ajdLj55fld!!sj"
SENTINEL_CONTAINER="redis-sentinel-1"
MASTER_CONTAINER="redis-master"
REPLICA1_CONTAINER="redis-replica-1"   # port 6380
REPLICA2_CONTAINER="redis-replica-2"   # port 6381
POLL_INTERVAL=0.2   # 초 단위 (200ms)

FAILOVER_MS=0
RECOVERY_MS=0
PROMOTED_CONTAINER=""
NEW_MASTER_ADDR=""

# ── 유틸리티 ──────────────────────────────────────────────────────────────────

ms_now() {
  powershell.exe -Command "[System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()" 2>/dev/null \
    | tr -d '\r\n '
}

sentinel_raw() {
  docker exec "$SENTINEL_CONTAINER" redis-cli -p 26379 --no-auth-warning "$@" 2>/dev/null \
    | tr -d '\r'
}

redis_cmd() {
  local container=$1 port=$2; shift 2
  docker exec "$container" redis-cli -p "$port" -a "$REDIS_PASSWORD" --no-auth-warning "$@" 2>/dev/null \
    | tr -d '\r'
}

# SENTINEL get-master-addr-by-name 결과: 두 줄(ip, port) → "ip:port" 형태로 반환
get_master_colon() {
  local result
  result=$(sentinel_raw SENTINEL get-master-addr-by-name "$MASTER_NAME")
  local ip port
  ip=$(echo "$result" | sed -n '1p' | tr -d ' ')
  port=$(echo "$result" | sed -n '2p' | tr -d ' ')
  echo "${ip}:${port}"
}

# port 번호만 반환
get_master_port() {
  sentinel_raw SENTINEL get-master-addr-by-name "$MASTER_NAME" | sed -n '2p' | tr -d ' '
}

port_to_container() {
  case "$1" in
    6379) echo "$MASTER_CONTAINER" ;;
    6380) echo "$REPLICA1_CONTAINER" ;;
    6381) echo "$REPLICA2_CONTAINER" ;;
    *)    echo "unknown:$1" ;;
  esac
}

log() {
  local ts
  ts=$(date '+%H:%M:%S' 2>/dev/null)
  printf "[%s] %s\n" "$ts" "$*"
}

sep() { echo "────────────────────────────────────────────────────────────"; }

# ── Replica 목록 출력 ─────────────────────────────────────────────────────────

print_replicas() {
  local raw
  raw=$(sentinel_raw SENTINEL replicas "$MASTER_NAME")
  local ips ports flags
  ips=$(echo "$raw"   | awk '/^ip$/{getline; print}')
  ports=$(echo "$raw" | awk '/^port$/{getline; print}')
  flags=$(echo "$raw" | awk '/^flags$/{getline; print}')

  local i=1
  while IFS= read -r ip; do
    local port flag cname
    port=$(echo "$ports" | sed -n "${i}p" | tr -d ' ')
    flag=$(echo "$flags" | sed -n "${i}p" | tr -d ' ')
    cname=$(port_to_container "$port")
    log "  Replica-$i: $ip:$port  container=[$cname]  flags=[$flag]"
    i=$((i+1))
  done <<< "$ips"
}

# ── 사전 점검 ─────────────────────────────────────────────────────────────────

preflight_check() {
  sep
  echo "  Redis Sentinel Failover 측정 스크립트"
  sep

  log "Docker 컨테이너 상태 확인..."
  local all_ok=true
  for c in "$SENTINEL_CONTAINER" redis-sentinel-2 redis-sentinel-3 \
            "$REPLICA1_CONTAINER" "$REPLICA2_CONTAINER"; do
    local status
    status=$(docker inspect --format '{{.State.Status}}' "$c" 2>/dev/null || echo "not found")
    if [[ "$status" != "running" ]]; then
      log "  [ERROR] '$c' 가 실행 중이 아닙니다 (status: $status)"
      all_ok=false
    else
      log "  ✓ $c : running"
    fi
  done

  # master는 없을 수도 있음 (이미 failover된 경우)
  local master_status
  master_status=$(docker inspect --format '{{.State.Status}}' "$MASTER_CONTAINER" 2>/dev/null || echo "not found")
  log "  - $MASTER_CONTAINER : $master_status"

  if ! $all_ok; then
    echo "  [ERROR] 필수 컨테이너가 실행 중이지 않습니다."
    exit 1
  fi

  log "Sentinel 연결 확인..."
  local addr
  addr=$(get_master_colon)
  if [[ -z "$addr" || "$addr" == ":" ]]; then
    echo "  [ERROR] Sentinel에서 master 정보를 가져올 수 없습니다."
    exit 1
  fi
  log "  ✓ 현재 Master: $addr"
}

# ── 현재 상태 출력 ────────────────────────────────────────────────────────────

print_current_state() {
  sep
  echo "  [초기 상태]"
  sep

  local addr
  addr=$(get_master_colon)
  local port="${addr##*:}"
  local cname
  cname=$(port_to_container "$port")
  log "현재 Master: $addr  container=[$cname]"

  log "Replica 목록:"
  print_replicas
}

# ── Failover 측정 ─────────────────────────────────────────────────────────────

measure_failover() {
  sep
  echo "  [Failover 측정]"
  sep

  local old_addr
  old_addr=$(get_master_colon)
  local old_port="${old_addr##*:}"
  log "기존 Master: $old_addr (container: $(port_to_container "$old_port"))"

  # Master 컨테이너 중지
  log "Master 컨테이너 중지: docker stop $MASTER_CONTAINER"
  local t_stop
  t_stop=$(ms_now)
  docker stop "$MASTER_CONTAINER" > /dev/null
  log "Master 컨테이너 중지 완료 (t_stop=0ms 기준)"

  # 새 master 선출까지 폴링
  log "새 Master 선출 대기 중 (${POLL_INTERVAL}s 간격 폴링)..."
  local t_elected="" new_addr=""

  while true; do
    sleep "$POLL_INTERVAL"

    local addr
    addr=$(get_master_colon 2>/dev/null || true)
    local port="${addr##*:}"

    if [[ -n "$port" && "$port" =~ ^[0-9]+$ && "$addr" != "$old_addr" ]]; then
      t_elected=$(ms_now)
      new_addr="$addr"
      break
    fi

    local elapsed=$(( $(ms_now) - t_stop ))
    if (( elapsed > 60000 )); then
      log "[TIMEOUT] 60초 내에 새 Master가 선출되지 않았습니다."
      exit 1
    fi
  done

  local failover_ms=$(( t_elected - t_stop ))
  local new_port="${new_addr##*:}"
  local promoted
  promoted=$(port_to_container "$new_port")

  log "✓ 새 Master 선출 완료!"
  log "  새 Master 주소 : $new_addr"
  log "  승격된 컨테이너: $promoted"
  log "  Failover 소요  : ${failover_ms}ms"

  # 새 Master에 쓰기 테스트
  sep
  echo "  [새 Master 쓰기 가능 여부 확인]"
  sep
  local write_result
  write_result=$(redis_cmd "$promoted" "$new_port" SET failover_test_key "$(date '+%Y%m%d_%H%M%S')" 2>/dev/null || echo "FAILED")
  if [[ "$write_result" == "OK" ]]; then
    log "✓ 새 Master 쓰기 성공 (SET → $write_result)"
  else
    log "✗ 새 Master 쓰기 실패 ($write_result)"
  fi

  # 새 Master의 replication 정보
  log "새 Master replication 정보:"
  redis_cmd "$promoted" "$new_port" INFO replication 2>/dev/null | \
    grep -E '^role:|^connected_slaves:|^slave[0-9]+:|^master_repl_offset:' | \
    sed 's/^/  /'

  FAILOVER_MS=$failover_ms
  PROMOTED_CONTAINER=$promoted
  NEW_MASTER_ADDR=$new_addr
}

# ── 복구 측정 ─────────────────────────────────────────────────────────────────

measure_recovery() {
  sep
  echo "  [Master 복구 측정 (원래 Master 재시작 → Replica 재합류)]"
  sep

  log "원래 Master 컨테이너 재시작: docker start $MASTER_CONTAINER"
  local t_restart
  t_restart=$(ms_now)
  docker start "$MASTER_CONTAINER" > /dev/null
  log "컨테이너 재시작 완료 (t_restart=0ms 기준)"

  # 원래 master(6379)가 sentinel에서 slave로 인식될 때까지 폴링
  log "원래 Master(6379)가 Replica로 재합류하길 대기 중..."
  local rejoined=false t_rejoined=0

  while true; do
    sleep "$POLL_INTERVAL"

    local raw
    raw=$(sentinel_raw SENTINEL replicas "$MASTER_NAME" 2>/dev/null || true)
    local ports flags
    ports=$(echo "$raw" | awk '/^port$/{getline; print}')
    flags=$(echo "$raw" | awk '/^flags$/{getline; print}')

    # 6379가 replica(slave) 상태인지 확인
    local i=1
    while IFS= read -r port; do
      port=$(echo "$port" | tr -d ' ')
      if [[ "$port" == "6379" ]]; then
        local flag
        flag=$(echo "$flags" | sed -n "${i}p" | tr -d ' ')
        if echo "$flag" | grep -q "slave"; then
          t_rejoined=$(ms_now)
          rejoined=true
          break 2
        fi
      fi
      i=$((i+1))
    done <<< "$ports"

    local elapsed=$(( $(ms_now) - t_restart ))
    if (( elapsed > 60000 )); then
      log "[TIMEOUT] 60초 내에 원래 Master가 Replica로 재합류하지 않았습니다."
      break
    fi
  done

  if $rejoined; then
    local rejoin_ms=$(( t_rejoined - t_restart ))
    log "✓ 원래 Master(6379)가 Replica로 재합류 완료!"
    log "  재합류 소요 시간: ${rejoin_ms}ms"
    RECOVERY_MS=$rejoin_ms
  else
    log "  복구 재합류 미확인 (타임아웃)"
    RECOVERY_MS=-1
  fi

  # 최종 상태
  sep
  echo "  [복구 후 최종 상태]"
  sep
  local final_addr
  final_addr=$(get_master_colon)
  local final_port="${final_addr##*:}"
  log "최종 Master: $final_addr  container=[$(port_to_container "$final_port")]"
  log "Replica 목록:"
  print_replicas
}

# ── 최종 리포트 ───────────────────────────────────────────────────────────────

print_report() {
  sep
  echo "  [측정 결과 요약]"
  sep
  printf "\n"
  printf "  Sentinel 설정\n"
  printf "    down-after-milliseconds : 5000 ms\n"
  printf "    failover-timeout        : 10000 ms\n"
  printf "    quorum                  : 2 / 3\n"
  printf "\n"
  printf "  Failover (Master 장애 → 새 Master 선출)\n"
  printf "    소요 시간   : %d ms\n" "$FAILOVER_MS"
  printf "    승격된 노드 : %s (%s)\n" "$PROMOTED_CONTAINER" "$NEW_MASTER_ADDR"
  printf "\n"
  printf "  Recovery (컨테이너 재시작 → Replica 재합류)\n"
  if (( RECOVERY_MS >= 0 )); then
    printf "    소요 시간   : %d ms\n" "$RECOVERY_MS"
  else
    printf "    소요 시간   : 측정 불가 (타임아웃)\n"
  fi
  printf "\n"
  sep
}

# ── 메인 ──────────────────────────────────────────────────────────────────────

preflight_check
print_current_state
measure_failover
measure_recovery
print_report

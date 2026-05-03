# Redis 단일 vs Sentinel 부하테스트 비교 보고서

## 환경

| 항목 | 값 |
|------|-----|
| 테스트 일자 | 2026-04-29 (단일 Redis staged + Sentinel staged) / 2026-05-01 (failover staged) |
| Redis | 7.4.1-alpine |
| Spring Boot | 3.4.0 / Java 17 |
| k6 | v1.7.1 |
| maxmemory | 500 MB (`allkeys-lru`) |
| OS | Windows 11 (Docker Desktop 4.34+, host networking) |
| DB | MariaDB 10.6 — 시드: 1,000 회원 + 1,000 피드백 |
| 실행 방식 | `constant-arrival-rate` per stage + stage 간 `cleanup-likes.sql` |

### 주요 변경 이력

| 일자 | 변경 항목 | 내용 |
|------|----------|------|
| 2026-04-28 | `@Retryable` 추가 | `LikeService.addLike / existsLike / deleteLike` — `retryFor = RedisConnectionFailureException.class, maxAttempts = 5, backoff = delay 500ms × 2.0` |
| 2026-04-28 | `RedisConfig` 통합 | standalone / sentinel 자동 분기 단일 팩토리 빈으로 리팩토링 |
| 2026-04-28 | Failover VU 인증 수정 | 단일 JWT 공유 → VU별 고유 계정(`loginByVu()`) |
| 2026-04-29 | 단일 Redis 재실행 | ramping 연속 실행 → staged(constant-arrival-rate) + stage 간 `cleanup-likes.sql` |
| 2026-04-29 | Sentinel 재실행 | 단일 Redis와 동일 조건 — staged + stage 간 `cleanup-likes.sql` |
| 2026-05-01 | Failover staged 재실행 | 100 / 200 / 400 RPS 3 stage, 각 stage에서 master kill + stage 간 `cleanup-likes.sql` |

---

## 시나리오별 결과

### 1. like-only (좋아요 단일 워크로드)

**단일 Redis — Staged (2026-04-29)**

| Stage | 목표 RPS | 실제 RPS | p95 | avg | 에러율 | p95<800ms | p99<2s | 드롭 |
|-------|---------|---------|-----|-----|-------|----------|-------|------|
| 1 · 200rps × 2m  | 200   | **194** | **89ms**    | 62ms    | 0% | ✅ | ✅ | 874 |
| 2 · 500rps × 3m  | 500   | **428** | **808ms**   | 314ms   | 0% | ❌ | ✅ | 13,071 |
| 3 · 1000rps × 3m | 1,000 | **402** | **1,533ms** | 973ms   | 0% | ❌ | ❌ | 107,769 |
| 4 · 1500rps × 3m | 1,500 | **399** | **2,343ms** | 1,458ms | 0% | ❌ | ❌ | 198,446 |

**Sentinel — Staged (2026-04-29)**

| Stage | 목표 RPS | 실제 RPS | p95 | avg | 에러율 | p95<800ms | p99<2s | 드롭 |
|-------|---------|---------|-----|-----|-------|----------|-------|------|
| 1 · 200rps × 2m  | 200   | **198** | **53ms**    | 23ms    | 0% | ✅ | ✅ | 283 |
| 2 · 500rps × 3m  | 500   | **444** | **700ms**   | 341ms   | 0% | ✅ | ✅ | 10,089 |
| 3 · 1000rps × 3m | 1,000 | **501** | **1,149ms** | 781ms   | 0% | ❌ | ✅ | 89,907 |
| 4 · 1500rps × 3m | 1,500 | **449** | **1,838ms** | 1,302ms | 0% | ❌ | ❌ | 189,340 |

**분석**
- 두 토폴로지 모두 stage 간 cleanup 적용 시 **에러율 0%** 유지.
- Sentinel이 단일 Redis 대비 **처리량 우위** (449 vs 399 req/s at 1500rps target) — replica 읽기 분산 효과로 추정.
- p95 임계(800ms) 초과 기점: 단일 Redis ~428 req/s, Sentinel ~501 req/s — Sentinel이 레이턴시 포화 기점도 높음.
- 단일 Redis p95 2,343ms vs Sentinel p95 1,838ms (1500rps stage) — **Sentinel이 21% 낮음**.

---

### 2. mixed (현실 워크로드 — 좋아요 60% + GEO 30% + 상태 10%)

**단일 Redis — Staged (2026-04-29)**

| Stage | 목표 RPS | 실제 RPS | p95 | avg | 에러율 | p95<800ms | 드롭 |
|-------|---------|---------|-----|-----|-------|----------|------|
| 1 · 200rps × 2m  | 200   | **195** | **34ms**    | 43ms    | 0% | ✅ | 741 |
| 2 · 600rps × 3m  | 600   | **497** | **932ms**   | 467ms   | 0% | ❌ | 18,609 |
| 3 · 1000rps × 3m | 1,000 | **475** | **1,327ms** | 823ms   | 0% | ❌ | 94,587 |
| 4 · 1200rps × 3m | 1,200 | **438** | **1,629ms** | 1,067ms | 0% | ❌ | 137,250 |

**Sentinel — Staged (2026-04-29)**

| Stage | 목표 RPS | 실제 RPS | p95 | avg | 에러율 | p95<800ms | 드롭 |
|-------|---------|---------|-----|-----|-------|----------|------|
| 1 · 200rps × 2m  | 200   | **195** | **31ms**    | 37ms    | 0% | ✅ | 744 |
| 2 · 600rps × 3m  | 600   | **437** | **1,079ms** | 536ms   | 0% | ❌ | 29,463 |
| 3 · 1000rps × 3m | 1,000 | **424** | **1,500ms** | 918ms   | 0% | ❌ | 103,881 |
| 4 · 1200rps × 3m | 1,200 | **433** | **1,665ms** | 1,074ms | 0% | ❌ | 138,193 |

**분석**
- 두 토폴로지 모두 에러율 0%. 1200rps stage에서 처리 RPS가 438 vs 433으로 **사실상 동등**.
- p95는 단일 Redis 1,629ms vs Sentinel 1,665ms — 차이 36ms로 **통계적으로 무의미한 수준**.
- Sentinel의 stage2 처리 RPS가 낮은 이유(437 vs 497): 600rps 목표에서 Sentinel은 VU 풀이 더 빠르게 소진 — 커넥션 수가 더 많이 필요함.

---

### 3. eviction-ramp (최대 부하 / Redis 메모리 압박)

**단일 Redis — Staged (2026-04-29)**

| Stage | 목표 RPS | 실제 RPS | p95 | avg | 에러율 | p95<800ms | 드롭 |
|-------|---------|---------|-----|-----|-------|----------|------|
| 1 · 300rps × 2m   | 300   | **297** | **24ms**    | 16ms    | 0%      | ✅ | 451 |
| 2 · 800rps × 3m   | 800   | **598** | **836ms**   | 516ms   | 0%      | ❌ | 36,235 |
| 3 · 1500rps × 3m  | 1,500 | **596** | **1,318ms** | 981ms   | 0%      | ❌ | 162,763 |
| 4 · 2500rps × 3m  | 2,500 | **573** | **2,071ms** | 1,689ms | 0%      | ❌ | 347,104 |
| 5 · 3000rps × 4m  | 3,000 | **420** | **4,151ms** | 2,760ms | **0.02%** | ❌ | 619,334 |

**Sentinel — Staged (2026-04-29)**

| Stage | 목표 RPS | 실제 RPS | p95 | avg | 에러율 | p95<800ms | 드롭 |
|-------|---------|---------|-----|-----|-------|----------|------|
| 1 · 300rps × 2m   | 300   | **297** | **49ms**    | 23ms    | 0%      | ✅ | 504 |
| 2 · 800rps × 3m   | 800   | **582** | **814ms**   | 537ms   | 0%      | ❌ | 39,319 |
| 3 · 1500rps × 3m  | 1,500 | **567** | **1,354ms** | 1,032ms | 0%      | ❌ | 168,049 |
| 4 · 2500rps × 3m  | 2,500 | **531** | **2,159ms** | 1,826ms | 0%      | ❌ | 354,757 |
| 5 · 3000rps × 4m  | 3,000 | **447** | **3,074ms** | 2,594ms | **0.03%** | ❌ | 612,494 |

**분석**
- 두 토폴로지 모두 **3,000rps stage에서 처음으로 에러 발생** — 단일 0.02%, Sentinel 0.03% (실질 차이 없음).
- 3,000rps stage에서 Sentinel이 단일 Redis 대비 처리량 높고(447 vs 420) p95 낮음(3,074ms vs 4,151ms) — like-only와 동일한 패턴.
- **eviction 미발생**: 두 토폴로지 모두 Redis 메모리 ~1.4MB 수준 유지 (500MB 한도의 0.28%).

---

### 4. failover (Sentinel 전용, master 종료: t=120s)

#### 이력 — 인증 방식 및 cleanup 개선

| 항목 | 2026-04-27 | 2026-04-28 |
|------|-----------|-----------|
| 인증 방식 | 단일 JWT 공유 (전 VU) | VU별 고유 계정 (`loginByVu()`) |
| 전체 에러율 | 99.99% | **27.73%** |
| avg (ms) | 31 | **3,895** |
| p95 (ms) | 25 | **8,150** |
| p99 (ms) | 58 | **10,701** |
| 처리 RPS | 188.4 | **98.1** |
| 총 요청 | — | 58,219 |
| k6 종료 방식 | 정상 종료 | **force-kill** (graceful-stop 무한 대기) |

> 2026-04-28 에러율 27.73%는 이전 비Staged 테스트에서 DB에 누적된 like 데이터 경합(중복 like)이 주원인. Redis/Sentinel 오버헤드 아님.

#### Staged (2026-05-01) — 100 / 200 / 400 RPS × 3 stage, stage 간 cleanup

각 stage: 2m warm-up → master kill (t=120s) → 6m failover 관찰 → 2m steady → 1m wind-down (총 11분)  
각 stage 종료 후 `cleanup-likes.sql` 실행. 매 stage마다 현재 master를 동적 탐지해 kill → Sentinel이 replica 승격.

| Stage | 목표 RPS | 실제 RPS | p95 | avg | max | 에러율 | p95<2000 | 드롭 | failover 소요 |
|-------|---------|---------|-----|-----|-----|-------|---------|------|------------|
| 100 RPS × 11m | 100 | **95.3** | **22ms**    | 53ms   | 11,435ms | **0%** | ✅ | 1,076  | ~5s |
| 200 RPS × 11m | 200 | **189.8** | **66ms**   | 76ms   | 11,881ms | **0%** | ✅ | 2,632  | ~5s |
| 400 RPS × 11m | 400 | **343.6** | **2,539ms** | 597ms | 25,422ms | **0%** | ❌ | 29,044 | ~4s |

**분석**

- **에러율 0% (전 stage)**: `@Retryable(maxAttempts=5, delay=500ms×2.0)`이 failover window(~5s) 동안 재시도해 모든 요청 처리 성공. 이전 27.73%는 DB 누적 데이터 경합이었음을 staged cleanup으로 최종 확인.
- **p95 정상 (100/200 RPS)**: 전체 11분 중 failover window(~5s)에 걸린 요청 비율이 낮아 p95가 정상 응답 수준(22–66ms) 유지.
- **p95 임계 초과 (400 RPS, 2,539ms)**: 400 RPS에서는 failover 순간 동시 재시도 체인에 진입하는 VU 수가 많아 p95가 2,000ms 임계를 초과. max=25,422ms로 일부 요청이 재시도 체인 최대 대기(≈ 7.5s + 오버헤드)를 소진.
- **failover 소요 시간 ~4–5s**: `down-after-milliseconds 5000`에 근접 — 설정값대로 선출 완료.
- 각 stage마다 다른 replica가 master로 승격됨 (100rps→6381, 200rps→6380, 400rps→6379 순환), 매회 복구 확인.

**Redis failover 동작 확인**

| 검증 항목 | 결과 |
|----------|------|
| failover 소요 시간 | ~4–5s (`down-after-milliseconds 5000` + 선출) |
| 승격 패턴 | replica-2(6381) → replica-1(6380) → master(6379) 순환 |
| 구 master 재합류 | replica로 자동 복귀 (1s 내 확인) |
| ShedLock 스킵 | 최대 1 사이클 (~5s 분량 좋아요 미플러시 가능) |
| Lettuce 재연결 | 자동 (앱 재시작 불필요) |

---

## 통합 매트릭스

| 시나리오 | 토폴로지 | 기준 부하 | p95 (ms) | avg (ms) | 에러율 | 처리 RPS |
|---------|---------|---------|---------|---------|-------|---------|
| like-only | single (staged)    | 1500rps target | 2,343 | 1,458 | 0%      | 399 |
| like-only | sentinel (staged)  | 1500rps target | 1,838 | 1,302 | 0%      | 449 |
| mixed     | single (staged)    | 1200rps target | 1,629 | 1,067 | 0%      | 438 |
| mixed     | sentinel (staged)  | 1200rps target | 1,665 | 1,074 | 0%      | 433 |
| eviction-ramp | single (staged)   | 3000rps target | 4,151 | 2,760 | 0.02%  | 420 |
| eviction-ramp | sentinel (staged) | 3000rps target | 3,074 | 2,594 | 0.03%  | 447 |
| failover (staged) | sentinel | 100 iters/s | 22 | 53 | 0% | 95.3 |
| failover (staged) | sentinel | 200 iters/s | 66 | 76 | 0% | 189.8 |
| failover (staged) | sentinel | 400 iters/s | 2,539† | 597 | 0% | 343.6 |

> † failover 400 RPS: p95<2000ms 임계 초과. 에러율은 0% (@Retryable 재시도 성공). 이전 비Staged 27.73% 에러는 DB 누적 데이터 경합 원인.

---

## 핵심 발견 사항

### 1. 동일 조건(staged + cleanup)에서 Sentinel 성능은 단일 Redis와 동등하거나 소폭 우세

| 시나리오 | 지표 | 단일 Redis | Sentinel | 차이 |
|---------|------|----------|---------|------|
| like-only 1500rps | 처리 RPS | 399 | **449** | Sentinel +13% |
| like-only 1500rps | p95 | 2,343ms | **1,838ms** | Sentinel -21% |
| mixed 1200rps | 처리 RPS | 438 | 433 | 동등 |
| mixed 1200rps | p95 | 1,629ms | 1,665ms | 동등 (+2%) |
| eviction 3000rps | 처리 RPS | 420 | **447** | Sentinel +6% |
| eviction 3000rps | p95 | 4,151ms | **3,074ms** | Sentinel -26% |

비Staged 테스트(2026-04-28)에서 관찰된 Sentinel의 극단적 지연(p95 11,261ms / 에러율 9.86%)은 **Redis 오버헤드가 아닌 DB 누적 데이터 경합**이 원인이었다. 동일 조건 staged 테스트에서는 Sentinel이 단일 Redis에 비해 성능 열위가 없다.

### 2. 높은 에러율의 실제 원인 — DB 누적 데이터 경합

| 구분 | like-only 에러율 | mixed 에러율 | eviction-ramp 에러율 |
|------|----------------|------------|-------------------|
| 비Staged — 데이터 누적 (2026-04-28) | 18.28% | 59.59% | 98.56% |
| Staged — stage 간 cleanup (2026-04-29) | **0%** | **0%** | **0.02%** |

stage 간 `cleanup-likes.sql`로 DB를 초기화하면 동일 부하에서 에러율이 0%로 수렴. 레이턴시 포화 지점(~400–580 req/s)은 두 토폴로지 모두 유사하여 실질 처리 한계는 **앱 HTTP 레이어**에 있음을 확인.

### 3. 실제 시스템 처리 한계 — Redis가 아닌 앱 HTTP 레이어

| 부하 수준 | 현상 |
|---------|------|
| ≤ 300 RPS | 정상 운용 (p95 < 100ms, 에러율 0%) |
| ~400–580 RPS | p95 800ms 초과, 처리량 포화 시작 (양 토폴로지 공통) |
| ~600 RPS | dropped_iterations 급증, 실질 처리량 정체 |
| ~3,000 RPS | 최초 에러 발생 (단일 0.02% / Sentinel 0.03%) |

### 4. @Retryable은 failover 복원력을 보장하지만 고부하(400 RPS+)에서 tail latency를 증폭시킨다

Staged failover 테스트(2026-05-01)로 @Retryable의 실효성을 최종 확인:

| 부하 | 에러율 | p95 | max | 평가 |
|------|-------|-----|-----|------|
| 100 RPS | **0%** | 22ms | 11,435ms | 완전 복구 — tail만 spike |
| 200 RPS | **0%** | 66ms | 11,881ms | 완전 복구 — tail만 spike |
| 400 RPS | **0%** | 2,539ms | 25,422ms | p95 임계(2,000ms) 초과 |

- 프로덕션 부하 수준(≤ 200 RPS)에서는 @Retryable이 failover window(~5s)를 에러 없이 흡수. p95에 거의 영향 없음.
- 400 RPS 이상에서는 동시에 재시도 체인에 진입하는 VU 수가 많아 p95가 임계를 초과. max latency는 재시도 backoff 합산(500+1000+2000+4000 = 7.5s) + 오버헤드.
- 이전 비Staged(2026-04-28) 27.73% 에러는 @Retryable 문제가 아닌 DB 누적 데이터 경합 — staged cleanup으로 완전 해소 재확인.

**권고**: Lettuce `commandTimeout` 조정 + `@Retryable` backoff.delay 단축(500ms → 100ms) 시 400 RPS 구간 p95 개선 가능.

### 5. eviction 미발생 — 현 워크로드에서 메모리 압박 없음

Redis 메모리 사용량이 ~1.4MB 수준을 유지해 500MB `allkeys-lru` 한도에 도달하지 않는다. `like:pending` Hash가 피드백 1,000개의 카운터만 저장하기 때문.

향후 캐시 전략 확장 시 `allkeys-lru`는 `like:pending`도 무차별 evict한다.  
**권고**: `volatile-lru` 전환 + 모든 캐시성 키에 TTL 명시.

### 6. ShedLock × Sentinel failover

failover window(~10s) 동안 Lettuce가 새 master 재탐색 중 `LikeJobScheduler`의 ShedLock 획득이 실패하면 한 사이클(10s) 스킵 가능. `lockAtMostFor: 9s` 설정으로 데드락은 방지되나, 해당 구간의 좋아요 플러시가 1회 지연됨(최대 20s 후 반영).

---

## 결론

### 토폴로지 비교

| 항목 | 단일 Redis (staged) | Sentinel (staged) | 권고 |
|------|-------------------|-----------------|-----|
| p95 (like-only, 1500rps) | 2,343ms | **1,838ms** | Sentinel 우위 |
| 처리 RPS (like-only) | 399 | **449** | Sentinel 우위 |
| 에러율 (like-only, staged) | 0% | 0% | 동등 |
| failover 자동 복구 | ❌ (수동) | ✅ (~4–5s + @Retryable) | Sentinel 필수 |
| failover 에러율 | N/A | **0%** (≤ 400 RPS) | @Retryable 유효 |
| failover 중 p95 | N/A | 66ms (200 RPS) / 2,539ms (400 RPS) | 400 RPS+ 임계 초과 |
| @Retryable 효과 | 거의 발동 안 함 | failover 에러 0% 달성 | backoff 단축으로 tail 개선 가능 |
| Redis 메모리 압박 | 없음 (~1.4MB) | 없음 | 현 워크로드 안전 |

> staged 조건(cleanup 적용)에서 Sentinel은 단일 Redis 대비 성능 열위 없음. 이전 비staged 비교(Sentinel p95 11,261ms)는 Redis 오버헤드가 아닌 DB 데이터 누적 효과였다.

### 처리 능력 요약 (양 토폴로지 staged 기준)

| RPS 구간 | 상태 | 단일 Redis p95 | Sentinel p95 |
|---------|------|-------------|------------|
| ≤ 300 RPS | 정상 운용 | < 100ms | < 100ms |
| 300 – 600 RPS | 레이턴시 포화 시작 | 800ms – 1,300ms | 700ms – 1,350ms |
| 600 – 1,500 RPS | 처리량 한계, 드롭 급증 | 1,300ms – 2,300ms | 1,150ms – 1,840ms |
| > 1,500 RPS | 포화, 드롭 폭발 | > 4,000ms | > 3,000ms |

**최종 권고**: Sentinel 토폴로지 프로덕션 채택. 단, 다음 조건을 선행할 것:
1. `@Retryable` backoff.delay 단축 (500ms → 100ms) — failover window tail latency 감소
2. Lettuce 커넥션 풀 `max-active` 및 `commandTimeout` 조정 — @Retryable 발동 빈도 억제
3. `volatile-lru` 전환 + `like:pending` TTL 미설정 — eviction 위험 방지

현 워크로드에서 실제 처리 한계는 Redis가 아닌 Spring Boot 앱 HTTP 레이어(~400–580 RPS)이므로, 향후 성능 개선은 스레드풀 / DB 커넥션풀 튜닝이 우선순위다.

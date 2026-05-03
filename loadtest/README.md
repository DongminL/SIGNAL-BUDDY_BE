# Signal Buddy — Redis 부하 테스트

단일 Redis 인스턴스와 Sentinel 토폴로지(1 master + 2 replica + 3 sentinel)의 성능을 k6로 비교 측정합니다.

---

## 사전 준비

| 도구 | 최소 버전 | 설치 |
|------|-----------|------|
| Docker Desktop | 4.34+ | docker.com |
| Node.js | 20+ | nodejs.org |
| k6 | 1.0+ | k6.io/docs/get-started/installation |
| mysql (CLI) | 10.6+ | (MariaDB 설치 시 포함) |

> `redis-cli`는 없어도 됩니다. PATH에 없으면 `docker exec <container> redis-cli` 로 자동 대체됩니다.

---

## 최초 1회 — 시드 데이터 주입

```bash
mysql -h 127.0.0.1 -u <user> -p<pass> signal-buddy < sql/seed-users.sql
```

생성 내용:
- 회원 4,000명 (`test0001@signal-buddy.com` ~ `test4000@signal-buddy.com`, 비밀번호: `password`)
- 피드백 (좋아요 대상)

> 로그인이 실패하면 `sql/seed-users.sql` 상단의 bcrypt 해시 생성 가이드를 참고해 비밀번호를 재생성하세요.

---

## 앱 기동

```bash
# 단일 Redis 모드
./gradlew bootRun --args='--spring.profiles.active=local'

# Sentinel 모드
./gradlew bootRun --args='--spring.profiles.active=local-sentinel'
```

---

## 테스트 실행

빌드(`npm run build`)는 실행 스크립트가 자동으로 처리합니다.  
`npm install`은 최초 1회만 필요합니다.

```bash
cd loadtest
npm install
```

### PowerShell (Windows)

```powershell
.\scripts\run.ps1 -Scenario like-only     -Topology single
.\scripts\run.ps1 -Scenario mixed         -Topology sentinel
.\scripts\run.ps1 -Scenario eviction-ramp -Topology single
.\scripts\run.ps1 -Scenario failover      -Topology sentinel
```

### Bash (Linux / macOS / WSL)

```bash
bash scripts/run.sh like-only single
bash scripts/run.sh mixed sentinel
bash scripts/run.sh eviction-ramp single
bash scripts/run.sh failover sentinel
```

### 환경변수 오버라이드

```bash
# Windows PowerShell
$env:BASE_URL="http://192.168.0.10:8080"
$env:DB_HOST="192.168.0.10"
$env:DB_USER="myuser"
$env:DB_PASS="mypass"
$env:DB_NAME="signal-buddy"
.\scripts\run.ps1 -Scenario like-only -Topology single

# Bash
BASE_URL=http://192.168.0.10:8080 DB_HOST=192.168.0.10 \
  bash scripts/run.sh like-only single
```

| 변수 | 기본값 |
|------|--------|
| `BASE_URL` | `http://localhost:8080` |
| `DB_HOST` | `127.0.0.1` |
| `DB_USER` | `cklol` |
| `DB_PASS` | *(내부 기본값)* |
| `DB_NAME` | `signal-buddy` |

---

## 시나리오 및 스테이지

실행 스크립트는 각 스테이지를 **개별 k6 프로세스**로 순차 실행하고,  
**스테이지가 끝날 때마다 자동으로 데이터를 초기화**합니다.

### 초기화 순서 (스테이지 종료 후 자동)

1. Redis 키 삭제 (`like:pending`, `like:processing`)
2. 12초 대기 — 실행 중인 배치 잡이 완료될 때까지
3. `sql/cleanup-likes.sql` 실행 — DB의 likes 행 및 `feedbacks.like_count` 리셋

### like-only

좋아요 단일 워크로드 베이스라인. 같은 피드백에 POST/DELETE 토글.

| 스테이지 | 목표 RPS | 지속 시간 |
|---------|---------|---------|
| 1 | 200 | 2분 |
| 2 | 500 | 3분 |
| 3 | 1000 | 3분 |
| 4 | 1500 | 3분 |

thresholds: `p(95) < 800ms`, `p(99) < 2000ms`, `error rate < 5%`

### mixed

좋아요 60% + 주변 교차로 조회(GEO) 40% 혼합 워크로드.

| 스테이지 | 목표 RPS | 지속 시간 |
|---------|---------|---------|
| 1 | 200 | 2분 |
| 2 | 600 | 3분 |
| 3 | 1000 | 3분 |
| 4 | 1200 | 3분 |

thresholds: `like-only`와 동일

### eviction-ramp

Redis `evicted_keys`가 처음 발생하는 RPS(최대 부하)를 산출합니다.  
thresholds 없음 — 측정 전용 시나리오.

| 스테이지 | 목표 RPS | 지속 시간 |
|---------|---------|---------|
| 1 | 300 | 2분 |
| 2 | 800 | 3분 |
| 3 | 1500 | 3분 |
| 4 | 2500 | 3분 |
| 5 | 3000 | 4분 |

### failover *(sentinel 전용)*

2분간 200 RPS 워밍업 → `redis-master` 자동 종료 → 8분간 sentinel 페일오버 관찰 → 1분 wind-down.

thresholds: `p(95) < 2000ms`, `error rate < 10%` (페일오버 윈도우 허용)

---

## 결과 파일 구조

```
results/
└── <yyyyMMddTHHmmssZ>-<scenario>-<topology>/
    ├── stage1-200rps/
    │   ├── summary.json    # k6 공식 요약 (thresholds 결과 포함)
    │   ├── raw.json        # 스트리밍 메트릭 (분 단위 RPS 집계용)
    │   └── evictions.csv   # 1초 단위 evicted_keys 타임라인
    ├── stage2-500rps/
    │   └── ...
    └── ...
```

---

## 결과 분석

```bash
# 특정 스테이지 요약
node scripts/analyze.mjs results/<ts>-like-only-single/stage1-200rps/

# 전체 시나리오 비교
node scripts/analyze.mjs results/<ts>-like-only-single/
```

---

## Sentinel failover 수동 검증

```bash
# 현재 마스터 확인
redis-cli -p 26379 -a ajdLj55fld!!sj --no-auth-warning sentinel get-master-addr-by-name mymaster

# 마스터 강제 종료 (failover 유발)
docker stop redis-master

# 5~10초 후 새 마스터 주소 확인
redis-cli -p 26379 -a ajdLj55fld!!sj --no-auth-warning sentinel get-master-addr-by-name mymaster
```

페일오버 윈도우(약 5–10초) 동안 `LikeJobScheduler`가 한 사이클(10초)을 스킵할 수 있습니다.  
`lockAtMostFor: 9s` 설정으로 데드락은 발생하지 않습니다.

---

## 결과 비교표

| 시나리오 | 토폴로지 | p95 (ms) | p99 (ms) | 실측 max RPS | eviction 시작 RPS | failover error rate |
|---------|---------|---------|---------|------------|-----------------|-------------------|
| like-only | single | | | | | — |
| like-only | sentinel | | | | | — |
| mixed | single | | | | | — |
| mixed | sentinel | | | | | — |
| eviction-ramp | single | — | — | | **측정값** | — |
| failover | sentinel | | | | — | |

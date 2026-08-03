# 메모리 용량 측정 실행 계획

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans로 태스크 단위 실행.
> 단계는 체크박스(`- [ ]`)로 추적한다.
> **이 계획의 특수 사정**: Task 7은 실제 치지직 라이브 방송이 여러 개 있을 때만 수행할 수 있다.
> Task 1~6은 언제든 독립적으로 완결되므로 먼저 끝낸 뒤 Task 7을 기다린다.

**Goal:** 클라이언트·채널·채팅이 늘어날 때 JVM·Redis·Node 데몬이 각각 먹는 메모리를 측정해,
항목당 증가분과 수용 한계를 숫자로 확보한다.

**Architecture:** 로컬 Docker에 배포와 동일한 JVM 옵션을 걸고, 축마다 부하를 단계적으로 올리며
각 지점에서 강제 GC 후 스냅샷을 뜬다. 부하 주입과 스냅샷은 셸 스크립트로 고정해 반복 재현한다.

**Tech Stack:** Docker Compose, `jcmd`(JDK 내장), `redis-cli`, Node `process.memoryUsage()`, bash.

**설계 문서:** `docs/superpowers/specs/2026-08-01-memory-capacity-measurement-design.md`

## Global Constraints

- 측정은 **로컬 Docker에서만** 한다. 한계까지 밀어붙이므로 운영 환경에서 하지 않는다.
- **Actuator를 추가하지 않는다.** 측정 도구가 측정 대상을 오염시킨다. `jcmd`로 충분하다.
- 힙을 읽기 전 **반드시 `jcmd 1 GC.run`** 을 먼저 실행한다. 생략하면 미수거 쓰레기까지 세게 된다.
- `-XX:NativeMemoryTracking=summary`는 5~10% 오버헤드가 있으므로 **측정 전용 설정에만** 넣고
  기본 실행 경로(`start.sh`, `docker-compose.yml`)는 건드리지 않는다.
- 모든 측정은 **원시 출력을 파일로 남긴다.** 파싱 결과만 남기면 나중에 재해석할 수 없다.
- 브랜치: `perf/memory-measurement`. 태스크당 1개 이상 커밋.
- 커밋 메시지는 한국어 한 줄, 접두사 없음 (이 저장소 관행).

---

### Task 1: 측정 전용 실행 설정

**Files:**
- Create: `docker-compose.measure.yml`

**Interfaces:**
- Produces: `docker compose -f docker-compose.yml -f docker-compose.measure.yml up -d` 로 기동되는
  측정용 스택. 백엔드 컨테이너명은 기존과 같은 `tongnamuking-backend`, Redis는 `tongnamuking-redis`.
- 컨테이너 메모리 예산을 **512MB로 명시**한다. 지금 로컬 compose에는 한도가 없어 "예산 대비
  몇 %" 계산이 불가능하다. 명시적 예산이 있어야 수용 한계를 산정할 수 있다.

- [x] **Step 1: 오버라이드 파일 작성**

> 실제 파일은 아래 YAML 과 달리 `java ...` 를 **한 줄로** 폈다. YAML 의 접힌 문자열(`>`)은
> 더 깊게 들여쓴 줄을 접지 않고 줄바꿈을 유지하므로, JVM 옵션을 보기 좋게 들여쓰면
> `-XX:MaxMetaspaceSize=128m` 이 별개 명령으로 실행되려다 깨진다.

```yaml
# docker-compose.measure.yml
# 측정 전용 오버라이드. 평소 실행 경로는 건드리지 않는다.
#   docker compose -f docker-compose.yml -f docker-compose.measure.yml up -d
services:
  backend:
    # 배포(start.sh)와 같은 JVM 상한 + 비힙 관측용 NMT
    command: >
      bash -c "
        cd /app/chat-collector &&
        if [ ! -d node_modules ]; then npm install; fi &&
        cd /app &&
        java -Xmx192m -Xms128m
          -XX:MaxMetaspaceSize=128m
          -XX:MaxDirectMemorySize=32m
          -Xss512k
          -XX:NativeMemoryTracking=summary
          -XX:+HeapDumpOnOutOfMemoryError
          -jar app.jar
      "
    # 수용 한계를 %로 말하려면 예산이 명시돼 있어야 한다
    mem_limit: 512m
```

- [x] **Step 2: 기동**

```bash
docker compose -f docker-compose.yml -f docker-compose.measure.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.measure.yml ps
```

Expected: `tongnamuking-backend`, `tongnamuking-redis`, `tongnamuking-mysql` 이 모두 Up.

오늘 자바 코드가 바뀌었다면 `--build` 를 반드시 붙인다. 없으면 옛 jar 가 그대로 돈다.
그리고 오버라이드가 실제로 걸렸는지 명령줄로 확인한다.

```bash
docker exec tongnamuking-backend ps -ef | grep "[j]ava"
```

Expected: `-Xmx192m -Xms128m -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=32m -Xss512k
-XX:NativeMemoryTracking=summary ...` 가 모두 보인다.

- [x] **Step 3: jcmd 가 붙는지 확인**

```bash
docker exec tongnamuking-backend jcmd 1 GC.heap_info
docker exec tongnamuking-backend jcmd 1 VM.native_memory summary | head -20
```

Expected: 첫 명령은 `used NNNNNK` 를 포함한 힙 정보. 두 번째는 `Java Heap`, `Class`,
`Thread` 등 카테고리별 reserved/committed 목록.

`VM.native_memory` 가 "Native memory tracking is not enabled" 를 내면 Step 1의 NMT 플래그가
적용되지 않은 것이다. `docker exec tongnamuking-backend ps aux | grep java` 로 실제 명령줄을
확인한다.

- [ ] **Step 4: 커밋**

```bash
git add docker-compose.measure.yml
git commit -m "측정 전용 compose 오버라이드 추가"
```

---

### Task 2: 데몬이 자기 메모리를 보고하게 한다

**Files:**
- Modify: `chat-collector/daemon.js:174-177` (`GET /health` 핸들러)

**Interfaces:**
- Produces: `GET http://127.0.0.1:3001/health` →
  `{"ok":true,"channels":N,"rss":바이트,"heapUsed":바이트,"external":바이트}`
- Task 3의 스냅샷 스크립트가 이 필드들을 읽는다.

컨테이너 안에서 Node PID를 찾아 `/proc/<pid>/status` 를 읽는 방법도 있으나, 데몬이 이미 HTTP
서버를 갖고 있으므로 자기 자신을 보고하게 하는 편이 안정적이다. `process.memoryUsage()` 는
JDK Actuator와 달리 내장 함수 호출이라 상시 켜두어도 무해하다.

- [ ] **Step 1: /health 핸들러 수정**

```js
  if (req.method === "GET" && url.pathname === "/health") {
    // 측정용: 이 데몬 프로세스가 실제로 쓰는 메모리를 함께 보고한다.
    // rss = OS가 이 프로세스에 할당한 실제 물리 메모리 (컨테이너 예산을 잠식하는 값)
    const mem = process.memoryUsage();
    sendJson(200, {
      ok: true,
      channels: channels.size,
      rss: mem.rss,
      heapUsed: mem.heapUsed,
      external: mem.external,
    });
    return;
  }
```

- [ ] **Step 2: 재기동 후 확인**

```bash
docker compose -f docker-compose.yml -f docker-compose.measure.yml restart backend
sleep 20
docker exec tongnamuking-backend curl -s http://127.0.0.1:3001/health
```

Expected: `{"ok":true,"channels":0,"rss":<수천만>,"heapUsed":<수백만>,"external":<수백만>}`

**데몬이 안 떠 있으면 연결 거부가 난다.** `CollectorDaemonManager`는 필요할 때 데몬을 띄우므로,
아직 구독이 하나도 없으면 프로세스가 없을 수 있다. 그 경우 Task 4의 클라이언트 부하 스크립트로
구독을 하나 만든 뒤 다시 확인하고, **"구독 0건일 때 데몬이 뜨는가"를 Task 5 베이스라인에
사실로 기록한다.** 이 사실 자체가 베이스라인 메모리를 좌우한다.

- [ ] **Step 3: 커밋**

```bash
git add chat-collector/daemon.js
git commit -m "데몬 health 응답에 프로세스 메모리 추가"
```

---

### Task 3: 스냅샷 스크립트

**Files:**
- Create: `scripts/measure/snapshot.sh`

**Interfaces:**
- Produces: `./scripts/measure/snapshot.sh <라벨>` 실행 시
  - `measurements/<라벨>/` 아래에 원시 출력 7개 파일 저장
  - 표준출력에 CSV 한 줄:
    `라벨,힙KB,메타스페이스KB,Redis바이트,Redis키수,축출키수,데몬RSS바이트,컨테이너,채널수`
- `Redis키수`(`DBSIZE`)와 `축출키수`(`evicted_keys`)는 Task 6이 요구한다. 전자는 설계 문서 §3이
  분리한 두 축 중 "키 개수"를, 후자는 LRU 축출이 시작됐는지를 나타낸다.

- [ ] **Step 1: 스크립트 작성**

```bash
#!/usr/bin/env bash
# scripts/measure/snapshot.sh <라벨>
# 각 계층의 메모리를 한 줄 CSV로 출력하고, 원시 출력은 파일로 남긴다.
set -euo pipefail

LABEL="${1:?사용법: snapshot.sh <라벨>}"
BACKEND=tongnamuking-backend
REDIS=tongnamuking-redis
OUT="measurements/${LABEL}"
mkdir -p "$OUT"

# ── 강제 GC ─────────────────────────────────────────────
# 이걸 빼면 "살아있는 객체가 얼마인가"가 아니라
# "GC가 마침 언제 돌았는가"를 재게 된다.
docker exec "$BACKEND" jcmd 1 GC.run > /dev/null
sleep 2

# ── 원시 출력 보존 ──────────────────────────────────────
docker exec "$BACKEND" jcmd 1 GC.heap_info            > "$OUT/heap_info.txt"
docker exec "$BACKEND" jcmd 1 VM.native_memory summary > "$OUT/nmt.txt"
docker exec "$REDIS" redis-cli INFO memory             > "$OUT/redis_info.txt"
docker exec "$REDIS" redis-cli INFO stats              > "$OUT/redis_stats.txt"
docker exec "$REDIS" redis-cli DBSIZE                  > "$OUT/redis_dbsize.txt"
docker exec "$BACKEND" curl -s http://127.0.0.1:3001/health > "$OUT/daemon_health.json" || \
  echo '{"ok":false,"note":"데몬 미기동"}' > "$OUT/daemon_health.json"
docker stats --no-stream --format '{{.Name}},{{.MemUsage}},{{.MemPerc}}' > "$OUT/docker_stats.txt"

# ── 파싱 ────────────────────────────────────────────────
# 힙은 "세대" 줄만 더한다. Task 1에서 확인한 실제 출력(SerialGC):
#
#   def new generation   total 39424K, used 18068K [...      ← 힙
#   tenured generation   total 87424K, used 37678K [...      ← 힙
#   Metaspace       used 100289K, committed 101184K, ...     ← 힙 아님
#     class space   used  13364K, committed  13760K, ...     ← Metaspace 의 일부
#
# 'used [0-9]+K' 를 전부 더하면 Metaspace 와 class space 까지 섞여 세 배 넘게 부푼다
# (169,399K vs 실제 55,746K). eden/from/to/the space 줄은 'NN% used' 형식이라
# 이 패턴에 걸리지 않으므로 이중 계산되지 않는다.
HEAP_KB=$(grep -E '(new|tenured) generation' "$OUT/heap_info.txt" \
          | grep -oE 'used [0-9]+K' | grep -oE '[0-9]+' | paste -sd+ - | bc)

# Metaspace 는 힙과 별개인 네이티브 영역이므로 따로 센다.
# heap_info 의 'Metaspace used' 줄에서 뽑는다 (NMT 없이도 얻을 수 있다).
META_KB=$(grep -E '^ *Metaspace' "$OUT/heap_info.txt" \
          | grep -oE 'used [0-9]+K' | grep -oE '[0-9]+')

REDIS_B=$(grep -oE '^used_memory:[0-9]+' "$OUT/redis_info.txt" | cut -d: -f2)

# 축출이 시작되면 메모리가 더 안 늘어난다 — 한도에 닿았다는 신호이지 여유가 아니다
EVICTED=$(grep -oE '^evicted_keys:[0-9]+' "$OUT/redis_stats.txt" | cut -d: -f2)

# DBSIZE 는 셸에서 숫자만 나오지만, 형식이 바뀌어도 견디도록 숫자만 뽑는다
KEYS=$(grep -oE '[0-9]+' "$OUT/redis_dbsize.txt" | head -1)

DAEMON_RSS=$(grep -oE '"rss":[0-9]+' "$OUT/daemon_health.json" | cut -d: -f2 || echo 0)
CHANNELS=$(grep -oE '"channels":[0-9]+' "$OUT/daemon_health.json" | cut -d: -f2 || echo 0)

CONTAINER=$(grep "^${BACKEND}," "$OUT/docker_stats.txt" | cut -d, -f2)

echo "${LABEL},${HEAP_KB:-0},${META_KB:-0},${REDIS_B:-0},${KEYS:-0},${EVICTED:-0},${DAEMON_RSS:-0},${CONTAINER},${CHANNELS:-0}"
```

- [ ] **Step 2: 실행 권한 부여 후 아이들 상태에서 실행**

```bash
chmod +x scripts/measure/snapshot.sh
./scripts/measure/snapshot.sh smoke
```

Expected: `smoke,45678,...` 형태의 CSV 한 줄(열 9개). 그리고 `measurements/smoke/` 에 파일 7개.
아무 부하도 없는 상태이므로 `축출키수`는 0이어야 한다. 0이 아니면 앞선 측정의 잔여 상태가
남아 있는 것이니 `redis-cli FLUSHDB` 후 다시 뜬다.

- [ ] **Step 3: 파싱 결과를 원시 파일과 대조**

```bash
cat measurements/smoke/heap_info.txt
```

"세대" 줄들의 `used NNNNNK` 를 눈으로 더해 CSV의 힙 값과 맞는지 확인한다.
**여기서 안 맞으면 이후 모든 측정이 틀린다.**

**출력 형식은 GC 종류에 묶여 있다.** 위 추출식은 Task 1에서 실제로 확인한 **SerialGC** 출력
(`def new generation` / `tenured generation`) 기준이다. 컨테이너 메모리와 CPU가 적어 JVM 이
SerialGC 를 고른 결과다. 예산을 키우거나 CPU 를 늘리면 JVM 이 G1 을 고를 수 있고, 그때는
`garbage-first heap total ..., used ...` 한 줄 형식이 되어 `(new|tenured) generation` 패턴이
아무것도 잡지 못한다(합계가 빈 문자열이 되어 CSV에 0 이 찍힌다).

따라서 **측정 조건을 바꾼 뒤에는 이 대조를 다시 한다.** 어느 GC 였는지도 결과 문서에 적는다.
현재 GC 는 아래로 확인한다.

```bash
docker exec tongnamuking-backend jcmd 1 VM.flags | tr ' ' '\n' | grep -i 'use.*gc'
```

- [ ] **Step 4: measurements/ 를 git에서 제외하고 커밋**

```bash
echo "measurements/" >> .gitignore
git add scripts/measure/snapshot.sh .gitignore
git commit -m "메모리 스냅샷 스크립트 추가"
```

---

### Task 4: 부하 주입 스크립트

**Files:**
- Create: `scripts/measure/load-clients.sh`
- Create: `scripts/measure/load-chat.sh`

**Interfaces:**
- Produces: `./scripts/measure/load-clients.sh <채널ID> <N>` — 서로 다른 `X-Client-ID` N개로 구독
- Produces: `./scripts/measure/load-chat.sh <채널ID> <건수> <동시성>` — 채팅 N건 주입
- 클라이언트 ID 규칙: `measure-client-<순번>` (Task 5의 정리 단계가 이 접두사로 지운다)

**Consumes:** `ClientIdentifierService`가 `X-Client-ID` 헤더를 클라이언트 식별자로 쓴다
(`ClientIdentifierService:23`). 헤더만 바꾸면 별개 클라이언트로 취급된다.

- [ ] **Step 1: 클라이언트 부하 스크립트 작성**

```bash
#!/usr/bin/env bash
# scripts/measure/load-clients.sh <채널ID> <N>
# 서로 다른 X-Client-ID 로 N명이 같은 채널을 구독한 상태를 만든다.
set -euo pipefail

CHANNEL="${1:?사용법: load-clients.sh <채널ID> <N>}"
COUNT="${2:?사용법: load-clients.sh <채널ID> <N>}"
BASE="${BASE_URL:-http://localhost:8080}"

for i in $(seq 1 "$COUNT"); do
  curl -s -o /dev/null -X POST \
    -H "X-Client-ID: measure-client-${i}" \
    "${BASE}/api/multi-channel-collection/start/${CHANNEL}"
done

echo "구독 요청 ${COUNT}건 전송 완료 (채널 ${CHANNEL})"
```

- [ ] **Step 2: 채팅 부하 스크립트 작성**

```bash
#!/usr/bin/env bash
# scripts/measure/load-chat.sh <채널ID> <건수> <동시성>
# 수집기 수신 엔드포인트에 채팅을 직접 밀어넣는다. 데몬도 치지직도 필요 없다.
# (MultiChannelControllerFanOutIsolationTest 가 쓰는 것과 같은 경로)
set -euo pipefail

CHANNEL="${1:?사용법: load-chat.sh <채널ID> <건수> <동시성>}"
TOTAL="${2:?}"
CONCURRENCY="${3:-10}"
BASE="${BASE_URL:-http://localhost:8080}"

send_one() {
  curl -s -o /dev/null -X POST \
    -H "Content-Type: application/json" \
    -d "{\"channelId\":\"${CHANNEL}\",\"channelName\":\"${CHANNEL}\",\"username\":\"chatter-$1\"}" \
    "${BASE}/api/multi-channel-collection/message/from-collector"
}
export -f send_one
export CHANNEL BASE

seq 1 "$TOTAL" | xargs -P "$CONCURRENCY" -I{} bash -c 'send_one {}'
echo "채팅 ${TOTAL}건 주입 완료 (동시성 ${CONCURRENCY})"
```

- [ ] **Step 3: 작은 수로 동작 확인**

```bash
chmod +x scripts/measure/load-clients.sh scripts/measure/load-chat.sh
./scripts/measure/load-clients.sh test-channel 5
curl -s "http://localhost:8080/api/multi-channel-collection/status" -H "X-Client-ID: measure-client-1"
./scripts/measure/load-chat.sh test-channel 20 5
docker exec tongnamuking-redis redis-cli --scan --pattern 'chat:rank:measure-client-*' | head
```

Expected: `status` 응답에 구독 정보가 보이고, Redis에 `chat:rank:measure-client-*` 키가 생긴다.

**구독이 0건으로 나올 수 있다.** `startCollection`은 데몬 구독이 실패하면 롤백하므로,
`test-channel` 같은 가짜 채널 ID로는 등록되지 않는다
(`MultiChannelControllerFanOutIsolationTest:31-34` 주석 참고). 이 경우:
- C축 측정은 **실제 라이브 채널 ID**를 써서 Task 6과 함께 진행하거나,
- `collector.daemon.enabled=false` 로 띄운 뒤 레지스트리 경로만 측정한다.

어느 쪽으로 갈지는 이 단계에서 실제 응답을 보고 정한 뒤 **Task 5 문서에 근거와 함께 기록**한다.

- [ ] **Step 4: 커밋**

```bash
git add scripts/measure/load-clients.sh scripts/measure/load-chat.sh
git commit -m "메모리 측정용 부하 주입 스크립트 추가"
```

---

### Task 5: 1·2단계 측정 — 베이스라인, 채팅 축, 클라이언트 축

**Files:**
- Create: `docs/superpowers/notes/2026-08-01-memory-measurement-results.md`

**Consumes:** Task 3의 `snapshot.sh`, Task 4의 두 부하 스크립트.

- [ ] **Step 1: 베이스라인 측정**

```bash
docker compose -f docker-compose.yml -f docker-compose.measure.yml down
docker compose -f docker-compose.yml -f docker-compose.measure.yml up -d
sleep 60   # 기동 직후 워밍업 요동이 가라앉기를 기다린다
./scripts/measure/snapshot.sh baseline
```

결과 문서에 **"구독 0건일 때 Node 데몬이 떠 있는가"** 를 함께 기록한다
(`daemon_health.json` 이 `"ok":false` 면 미기동).

- [ ] **Step 2: 채팅 축 측정**

각 지점마다 부하 주입 → 스냅샷 순으로 진행한다. 누적이므로 되돌리지 않는다.

```bash
./scripts/measure/load-chat.sh <채널> 1000 20   && ./scripts/measure/snapshot.sh chat-1k
./scripts/measure/load-chat.sh <채널> 9000 20   && ./scripts/measure/snapshot.sh chat-10k
./scripts/measure/load-chat.sh <채널> 40000 20  && ./scripts/measure/snapshot.sh chat-50k
```

- [ ] **Step 3: 클라이언트 축 측정**

```bash
docker compose -f docker-compose.yml -f docker-compose.measure.yml restart backend
sleep 60
./scripts/measure/snapshot.sh client-0
./scripts/measure/load-clients.sh <채널> 10  && ./scripts/measure/snapshot.sh client-10
./scripts/measure/load-clients.sh <채널> 40  && ./scripts/measure/snapshot.sh client-50
./scripts/measure/load-clients.sh <채널> 50  && ./scripts/measure/snapshot.sh client-100
```

구독만으로는 Redis 키가 생기지 않는다(랭킹 키는 첫 채팅 때 lazy 생성). 클라이언트 축의 Redis
비용을 보려면 각 지점에서 **채팅을 소량 주입해 키를 실체화**한 뒤 스냅샷을 떠야 한다.

```bash
./scripts/measure/load-chat.sh <채널> 100 10   # 키 실체화용
```

- [ ] **Step 4: 결과 문서 작성**

`docs/superpowers/notes/2026-08-01-memory-measurement-results.md` 에 축별 표를 채운다.

```markdown
## 채팅 축 (채널 1개, 클라이언트 1명 고정)

| 누적 채팅 | JVM 힙 KB | 메타스페이스 KB | Redis B | 데몬 RSS B | 컨테이너 |
|---|---|---|---|---|---|
| 0 | | | | | |
| 1,000 | | | | | |
| 10,000 | | | | | |
| 50,000 | | | | | |
| **건당 증가분** | | | | | |
```

각 표 아래에 **가설(설계 문서 §3)과 맞았는지**를 한 줄로 적는다. 빗나갔으면 그게 발견이다.

- [ ] **Step 5: 커밋**

```bash
git add docs/superpowers/notes/2026-08-01-memory-measurement-results.md
git commit -m "채팅 축과 클라이언트 축 메모리 측정 결과 기록"
```

---

### Task 6: 3단계 측정 — 시간 경과 축 (버킷 누적)

**Files:**
- Create: `scripts/measure/load-buckets.sh`
- Modify: `docs/superpowers/notes/2026-08-01-memory-measurement-results.md`

**Interfaces:**
- Produces: `./scripts/measure/load-buckets.sh <클라이언트ID> <채널명> <분수> <멤버수>` —
  현재 시각부터 과거로 `<분수>`개의 버킷 키를 만들고, 각 키에 `<멤버수>`명을 넣는다.
- 키 이름은 `chat:rank:{client}:{channel}:b:{yyyyMMddHHmm}` — `ChatRankingService.bucketKey`와
  같은 규칙이어야 한다. 다르면 실제와 다른 것을 재게 된다.

R 축(채팅 유입)은 짧은 시간에 몰아넣으므로 버킷이 1~2개밖에 생기지 않는다. 실제 운영에서는
1분마다 버킷이 하나씩 생겨 2시간치인 최대 120개가 공존한다. 이 누적분을 시간을 기다리지 않고
합성해서 잰다.

- [ ] **Step 1: 버킷 합성 스크립트 작성**

```bash
#!/usr/bin/env bash
# scripts/measure/load-buckets.sh <클라이언트ID> <채널명> <분수> <멤버수>
# 과거 N분에 해당하는 버킷 키를 직접 만들어, 2시간 누적 상태를 즉시 재현한다.
# 키 이름 규칙은 ChatRankingService.bucketKey 와 동일해야 한다.
set -euo pipefail

CLIENT="${1:?사용법: load-buckets.sh <클라이언트ID> <채널명> <분수> <멤버수>}"
CHANNEL="${2:?}"
MINUTES="${3:?}"
MEMBERS="${4:-50}"
REDIS=tongnamuking-redis

BASE_KEY="chat:rank:${CLIENT}:${CHANNEL}"

for m in $(seq 0 $((MINUTES - 1))); do
  # 현재 시각에서 m분 전 (컨테이너 안 date 는 GNU date)
  STAMP=$(docker exec "$REDIS" date -u -d "-${m} minutes" +%Y%m%d%H%M)
  KEY="${BASE_KEY}:b:${STAMP}"

  # 멤버 <멤버수>명을 한 번에 넣는다 (ZADD 는 ZINCRBY 와 같은 자료구조를 만든다)
  ARGS=""
  for u in $(seq 1 "$MEMBERS"); do
    ARGS="${ARGS} 1 chatter-${u}"
  done
  # shellcheck disable=SC2086
  docker exec "$REDIS" redis-cli ZADD "$KEY" $ARGS > /dev/null
  docker exec "$REDIS" redis-cli EXPIRE "$KEY" 7200 > /dev/null
done

echo "버킷 키 ${MINUTES}개 생성 완료 (${BASE_KEY}:b:*, 키당 멤버 ${MEMBERS}명)"
```

- [ ] **Step 2: 키 이름 규칙이 실제와 같은지 대조**

```bash
chmod +x scripts/measure/load-buckets.sh
./scripts/measure/load-buckets.sh verify-client verify-channel 2 3
docker exec tongnamuking-redis redis-cli --scan --pattern 'chat:rank:verify-client:*'
docker exec tongnamuking-redis redis-cli TYPE chat:rank:verify-client:verify-channel:b:$(date -u +%Y%m%d%H%M)
```

Expected: `chat:rank:verify-client:verify-channel:b:202608021432` 형태의 키 2개, 타입은 `zset`.

**타임존에 주의한다.** 스크립트는 `date -u`(UTC)를 쓰는데 애플리케이션은
`LocalDateTime.now()`(컨테이너 로컬 시간)를 쓴다. 둘이 다르면 이름이 어긋난다. 실제 애플리케이션이
만든 버킷 키와 비교해 확인한다:

```bash
./scripts/measure/load-chat.sh <채널> 10 2
docker exec tongnamuking-redis redis-cli --scan --pattern 'chat:rank:*:b:*' | tail -5
```

애플리케이션이 만든 키의 타임스탬프와 스크립트가 만든 것이 같은 분을 가리켜야 한다. 어긋나면
스크립트의 `date -u` 에서 `-u` 를 빼거나 `TZ=Asia/Seoul` 을 지정한다.

- [ ] **Step 3: 버킷을 늘려가며 측정**

```bash
docker compose -f docker-compose.yml -f docker-compose.measure.yml restart backend
sleep 60
docker exec tongnamuking-redis redis-cli FLUSHDB   # 앞선 축의 잔여 키 제거
./scripts/measure/snapshot.sh bucket-0

./scripts/measure/load-buckets.sh measure-client-1 ch1 30 50  && ./scripts/measure/snapshot.sh bucket-30
./scripts/measure/load-buckets.sh measure-client-1 ch1 60 50  && ./scripts/measure/snapshot.sh bucket-60
./scripts/measure/load-buckets.sh measure-client-1 ch1 120 50 && ./scripts/measure/snapshot.sh bucket-120
```

같은 클라이언트·채널에 분수만 늘리므로 키가 덮어써지며 누적된다.

- [ ] **Step 4: 키당 멤버 수 축도 따로 잰다**

키 개수와 키 크기는 별개 축이다(설계 문서 §3). 버킷 수를 120으로 고정하고 멤버 수만 늘린다.

```bash
docker exec tongnamuking-redis redis-cli FLUSHDB
./scripts/measure/load-buckets.sh measure-client-1 ch1 120 10   && ./scripts/measure/snapshot.sh member-10
docker exec tongnamuking-redis redis-cli FLUSHDB
./scripts/measure/load-buckets.sh measure-client-1 ch1 120 100  && ./scripts/measure/snapshot.sh member-100
docker exec tongnamuking-redis redis-cli FLUSHDB
./scripts/measure/load-buckets.sh measure-client-1 ch1 120 1000 && ./scripts/measure/snapshot.sh member-1000
```

Redis는 작은 Sorted Set을 `listpack`으로 압축 저장하다가 임계치를 넘으면 `skiplist`로 바꾼다.
**멤버 수를 늘리다 보면 어느 지점에서 메모리가 계단식으로 뛴다.** 그 지점을 결과에 기록한다.

```bash
docker exec tongnamuking-redis redis-cli OBJECT ENCODING chat:rank:measure-client-1:ch1:b:<분>
```

- [ ] **Step 5: 결과 기록**

결과 문서에 표 두 개를 추가한다.

```markdown
## 시간 경과 축 (버킷 누적, 키당 멤버 50명 고정)

| 버킷 수 | 키 개수 | Redis B | 버킷당 증가분 | evicted_keys |
|---|---|---|---|---|
| 0 | | | | |
| 30 | | | | |
| 60 | | | | |
| 120 | | | | |

> 이 수치는 시간을 기다리지 않고 버킷 키를 합성해 만든 것이다 (설계 문서 §5).

## 키당 멤버 수 축 (버킷 120개 고정)

| 멤버 수 | Redis B | OBJECT ENCODING | 멤버당 증가분 |
|---|---|---|---|
| 10 | | | listpack? |
| 100 | | | |
| 1,000 | | | skiplist? |
```

- [ ] **Step 6: 커밋**

```bash
git add scripts/measure/load-buckets.sh docs/superpowers/notes/2026-08-01-memory-measurement-results.md
git commit -m "시간 경과 축과 키당 멤버 수 축 측정 결과 기록"
```

---

### Task 7: 4단계 측정 — 채널 축과 데몬화 전후 비교

**사전조건:** 치지직에 라이브 중인 채널이 최소 8개 필요하다. 채널 ID는 치지직 웹에서
스트리머 페이지 URL의 해시값으로 확보한다.

**Files:**
- Modify: `docs/superpowers/notes/2026-08-01-memory-measurement-results.md`

- [ ] **Step 1: 라이브 채널 ID 목록 확보**

```bash
# 확보한 ID를 파일로 모아둔다 (재현 시 어떤 채널이었는지 남기기 위함)
cat > measurements/live-channels.txt <<'EOF'
b68af124ae2f1743a1dcbf5e2ab41e0b
# ... 라이브 중인 채널 ID를 한 줄에 하나씩
EOF
```

- [ ] **Step 2: 채널을 하나씩 늘리며 측정**

```bash
docker compose -f docker-compose.yml -f docker-compose.measure.yml restart backend
sleep 60
./scripts/measure/snapshot.sh channel-0

i=0
while read -r ch; do
  [[ "$ch" =~ ^# ]] && continue
  i=$((i+1))
  curl -s -o /dev/null -X POST -H "X-Client-ID: measure-channel-probe-${i}" \
    "http://localhost:8080/api/multi-channel-collection/start/${ch}"
  sleep 10   # WebSocket 연결과 초기 버퍼가 자리잡기를 기다린다
  ./scripts/measure/snapshot.sh "channel-${i}"
done < measurements/live-channels.txt
```

클라이언트당 채널 3개 제한(`getMaxChannelsPerClient`)이 있으므로 채널마다 다른
`X-Client-ID`를 쓴다. 같은 클라이언트로 4번째 채널을 구독하면 거부된다.

- [ ] **Step 3: 데몬화 전 방식의 비용 측정**

`DogCakeCollectionService`가 아직 채널당 프로세스를 띄우는 방식(`ProcessBuilder`)을 쓴다.
이 경로로 수집을 한 번 시작시켜 **Node 프로세스 하나의 RSS**를 잰다.

```bash
docker exec tongnamuking-backend bash -c \
  "ps -o rss=,cmd= -C node | sort -rn | head"
```

Expected: `node .../daemon.js` 와 별개로 `node .../index.js` 프로세스가 보이고, 각각의 RSS(KB)를
얻는다. 이 값이 **채널 1개당 프로세스 비용**의 기준값이다.

- [ ] **Step 4: 비교표 작성**

```markdown
## 채널 축 — 데몬화 전후

| 채널 수 | 데몬 방식 RSS | 프로세스 방식 추정 (프로세스당 X MB × N) | 절감 |
|---|---|---|---|
| 1 | | | |
| 4 | | | |
| 8 | | | |
```

프로세스 방식은 실제로 8개를 띄우면 컨테이너가 죽을 수 있으므로, **1개 실측값 × N** 으로
추정하고 그 사실을 표에 명시한다.

- [ ] **Step 5: 커밋**

```bash
git add docs/superpowers/notes/2026-08-01-memory-measurement-results.md measurements/live-channels.txt
git commit -m "채널 축 메모리 측정과 데몬화 전후 비교 기록"
```

---

### Task 8: 결론 — 수용 한계 산정과 설정 재검토

**Files:**
- Modify: `docs/superpowers/notes/2026-08-01-memory-measurement-results.md`
- Modify: `start.sh` (숫자에 근거가 생겼을 때만)

- [ ] **Step 1: 축별 수용 한계 계산**

각 축의 항목당 증가분과 컨테이너 예산(512MB)으로 계산해 결과 문서에 적는다.

```
채널 수용 한계 = (예산 − 베이스라인 − JVM 상한 합) ÷ 채널당 증가분
```

세 축 중 **무엇이 먼저 한계에 닿는지**를 한 문단으로 정리한다.

- [ ] **Step 2: 가설 검증 결과 정리**

설계 문서 §3의 가설 표를 그대로 옮겨 적고, 각 칸에 실측이 맞았는지 표시한다. 빗나간 칸은
왜 빗나갔는지 원시 출력(`measurements/*/nmt.txt` 등)을 근거로 설명한다.

- [ ] **Step 3: JVM 상한 재검토**

현재 `-Xmx192m`은 OOM 대응으로 급히 조인 값이라 근거가 없다. 측정으로 확보한 실사용량을
근거로 조정이 필요한지 판단한다.

- 힙 실사용이 상한에 한참 못 미치면 → 낮춰 Node 몫을 늘린다
- 상한 가까이 붙어 있으면 → 컨테이너 예산 자체를 늘려야 한다는 근거가 된다

**숫자에 근거가 생겼을 때만 `start.sh`를 고친다.** 근거 없이 조정하면 지금과 같은 상태가 된다.

- [ ] **Step 4: 커밋**

```bash
git add docs/superpowers/notes/2026-08-01-memory-measurement-results.md
git commit -m "메모리 수용 한계 산정과 가설 검증 결과 정리"
```

---

## Self-Review 결과

- **스펙 커버리지**: 설계 문서 §2의 네 축이 Task 5(C·R), Task 6(T), Task 7(M)에 매핑됨.
  §4 측정 환경 → Task 1, §5 부하 생성 → Task 4·6, §6 측정 절차 → Task 3(스크립트로 고정),
  §7 진행 순서 → Task 5·6·7, §8 산출물 → Task 5·6·7·8. §3 가설 검증은 Task 8 Step 2에 배치.
  §3이 분리한 "키 개수 / 키당 멤버 수" 두 축은 각각 Task 6 Step 3·4에 대응한다.
- **타입 일관성**: 컨테이너명 `tongnamuking-backend`/`tongnamuking-redis`, 데몬 포트 3001,
  클라이언트 ID 접두사 `measure-client-`, 버킷 키 규칙
  `chat:rank:{client}:{channel}:b:{yyyyMMddHHmm}`, 스냅샷 CSV 열 순서 — 태스크 간 참조 일치 확인.
- **알려진 불확실성 3개** (해당 태스크에 처리 지침 포함):
  1. `snapshot.sh`의 힙 파싱은 수집기 종류에 따라 출력이 달라질 수 있다 → Task 3 Step 3에서
     원시 출력과 대조하는 단계를 필수로 뒀다.
  2. 가짜 채널 ID로는 구독이 롤백될 수 있다 → Task 4 Step 3에서 실제 응답을 보고 C축 진행
     방식을 정하도록 했다.
  3. 버킷 키 합성 시 타임존이 애플리케이션과 어긋날 수 있다 → Task 6 Step 2에서 애플리케이션이
     만든 실제 키와 대조하는 단계를 뒀다.

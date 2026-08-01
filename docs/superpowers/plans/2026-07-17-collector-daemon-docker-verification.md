# 수집기 데몬화 — Docker 통합 검증 절차

계획서 `2026-07-17-collector-daemon.md`의 Task 5를 Docker 환경에 맞게 옮긴 것.
Task 5 원문은 Windows + `bootRun` 기준이라 `/health` 접근과 프로세스 측정 방법이 다르다.

**Docker로 하는 게 더 정확하다.** Railway가 리눅스 컨테이너인데 Task 5의 기준값(빈 Node 44MB)은
Windows에서 잰 것이라 운영값이 아니다. 여기서는 리눅스에서 재고 `docker stats`로 컨테이너
메모리를 직접 본다.

## 사전 확인 (코드 읽고 확인한 것들 — 설정 변경 불필요)

- `tongnamuking-backend/Dockerfile`이 Node 18을 설치한다. `daemon.js`가 쓰는 건 `node:http`와
  `Promise.race`뿐이라 18에서 동작한다.
- `docker-compose.yml`이 `./chat-collector`를 `/app/chat-collector`에 볼륨 마운트한다.
  이게 `collector.daemon.script`의 기본값(`/app/chat-collector/daemon.js`)과 일치하므로
  환경변수를 따로 줄 필요가 없다.
- `node_modules`에 네이티브 바이너리가 없어 Windows에서 설치된 것이 리눅스 컨테이너에서도 동작한다.
  compose의 `command`가 `node_modules`가 있으면 `npm install`을 건너뛴다.
- `BACKEND_URL: http://backend:8080` — 데몬이 자기 컨테이너로 되돌아오는 DNS 이름으로 POST한다. 정상.
- 데몬은 컨테이너 안 `127.0.0.1:3001`에 바인딩하고 compose는 8080만 노출한다.
  **따라서 호스트에서 `curl http://127.0.0.1:3001/health`는 실패한다.** `docker exec`로 들어가야 한다.

## 준비물

방송 중인 치지직 채널 ID 3개. `https://chzzk.naver.com/live/<채널ID>` 의 마지막 부분.
아래에서 `<CH1>`, `<CH2>`, `<CH3>`으로 표기한다.

---

## 0. 기동

```bash
docker-compose up -d --build
```

`--build`가 필요하다. 안 붙이면 이전 이미지의 옛 jar를 쓴다.

```bash
docker-compose logs -f backend
```

기대 로그 (순서대로):
- `수집기 데몬 시작 (PID: ..., script: /app/chat-collector/daemon.js)`
- `[collector] 수집기 데몬 준비 완료 — 127.0.0.1:3001, 백엔드 http://backend:8080`

`script:` 경로가 `/app/chat-collector/daemon.js`가 아니면 볼륨 마운트가 어긋난 것이다.

## 1. 데몬 생존 확인

```bash
docker exec tongnamuking-backend curl -s http://127.0.0.1:3001/health
```

기대: `{"ok":true,"channels":0}`

## 2. 프로세스 기준선

```bash
docker top tongnamuking-backend
```

`docker top`은 호스트에서 도는 것이라 컨테이너에 `ps`가 없어도 된다.
기대: `java -jar app.jar` 1개 + `node /app/chat-collector/daemon.js` 1개.

## 3. 늦은 구독자가 0부터 시작하는가 (가장 중요)

**이번 변경에서 깨질 수 있는 유일한 의미론이고, 깨져도 에러 없이 숫자만 틀린다.**

클라이언트 A로 수집 시작:

```bash
curl -s -X POST http://localhost:8080/api/multi-channel-collection/start/<CH1> -H "X-Client-ID: test-A"
```

기대: `{"success":true,...}`

채널명을 알아낸다 (Redis 키에 채널명이 들어가므로):

```bash
docker exec tongnamuking-redis redis-cli KEYS "chat:rank:test-A:*"
```

**60초 이상 기다린다.** 채팅이 쌓여야 한다. A의 순위 확인:

```bash
docker exec tongnamuking-redis redis-cli ZREVRANGE "chat:rank:test-A:<채널명>" 0 4 WITHSCORES
```

기대: 닉네임과 점수가 나온다.

이제 B를 구독시키기 **직전에** B 키가 없는지 확인:

```bash
docker exec tongnamuking-redis redis-cli EXISTS "chat:rank:test-B:<채널명>"
```

기대: `0`

B 구독:

```bash
curl -s -X POST http://localhost:8080/api/multi-channel-collection/start/<CH1> -H "X-Client-ID: test-B"
docker exec tongnamuking-redis redis-cli EXISTS "chat:rank:test-B:<채널명>"
```

기대: 여전히 `0`. **구독만으로는 Redis를 건드리지 않는다.** 키는 다음 채팅이 올 때 생긴다.

30초 뒤 비교:

```bash
docker exec tongnamuking-redis redis-cli ZREVRANGE "chat:rank:test-B:<채널명>" 0 4 WITHSCORES
docker exec tongnamuking-redis redis-cli ZCARD "chat:rank:test-A:<채널명>"
docker exec tongnamuking-redis redis-cli ZCARD "chat:rank:test-B:<채널명>"
```

**판정: B의 점수 총합이 A보다 현저히 작아야 한다.** A는 1분 30초를 셌고 B는 30초를 셌다.
B가 A와 같으면 팬아웃이 과거 채팅까지 소급한 것이므로 **실패**다.

## 4. 프로세스가 1개인가 (이 작업의 목적)

서로 다른 채널 2개를 더 붙인다:

```bash
curl -s -X POST http://localhost:8080/api/multi-channel-collection/start/<CH2> -H "X-Client-ID: test-C"
curl -s -X POST http://localhost:8080/api/multi-channel-collection/start/<CH3> -H "X-Client-ID: test-D"
```

```bash
docker top tongnamuking-backend
```

**판정: `daemon.js` 프로세스가 정확히 1개.** 클라이언트는 4명(A,B,C,D), 채널은 3개다.
변경 전이었다면 여기서 수집기 프로세스가 4개였다. **1개가 아니면 이 작업은 실패다.**

```bash
docker stats --no-stream tongnamuking-backend
```

컨테이너 전체 메모리(JVM + 데몬). 리눅스 실측값이므로 이 숫자를 기록할 것.

## 5. 중복 채널이 커넥션을 공유하는가

```bash
docker exec tongnamuking-backend curl -s http://127.0.0.1:3001/health
```

기대: `{"ok":true,"channels":3}` — A와 B가 같은 채널이라 클라이언트 4명에 채널은 3개.

## 6. 데몬을 죽였을 때 복구되는가

이 검증이 중요한 이유: 최종 리뷰에서 **바로 이 경로에 Critical 결함**이 있었다.
`startDaemon()`이 프로세스만 띄우고 반환하는데 Node가 포트를 여는 데 ~180ms 걸려,
재구독 POST가 매번 connection refused로 실패하고 재시도가 없었다. `/health` readiness
게이트로 고쳤고, 이 단계가 그 수정의 실증이다.

```bash
docker exec tongnamuking-backend pkill -f daemon.js
```

`pkill`이 없다면 (`procps` 미설치 이미지일 수 있음):

```bash
docker exec tongnamuking-backend sh -c 'kill $(ps -eo pid,args | grep "[d]aemon.js" | awk "{print \$1}")'
```

그것도 안 되면 컨테이너를 재시작해서 기동 경로만 확인한다: `docker-compose restart backend`

로그를 본다:

```bash
docker-compose logs -f backend
```

기대 순서:
1. `수집기 데몬이 예기치 않게 종료됨 (exit: ...). 재시작합니다.`
2. `1000ms 후 수집기 데몬을 재시작합니다`
3. `수집기 데몬 시작 (PID: ...)`
4. 데몬 준비 대기 후 `수집기 데몬 재구독 시작: 3개 채널`
5. `[collector] 구독 시작: ...` × 3

```bash
docker exec tongnamuking-backend curl -s http://127.0.0.1:3001/health
```

기대: `{"ok":true,"channels":3}`

**여기서 멈추지 말 것.** 최종 리뷰어가 지적한 대로, 커넥션 수가 복구된 것과
데이터가 실제로 흐르는 것은 다른 주장이고 사용자가 받는 건 후자다.
기존 구독자의 집계가 **계속 늘어나는지** 확인한다:

```bash
docker exec tongnamuking-redis redis-cli ZCARD "chat:rank:test-A:<채널명>"
# 30초 대기
docker exec tongnamuking-redis redis-cli ZCARD "chat:rank:test-A:<채널명>"
```

**판정: 두 번째 값이 더 커야 한다.** 안 늘면 데몬은 붙었지만 데이터 경로가 죽은 것이다.

## 7. 마지막 구독자가 빠지면 커넥션이 해제되는가

```bash
curl -s -X POST http://localhost:8080/api/multi-channel-collection/stop/<CH1> -H "X-Client-ID: test-A"
docker exec tongnamuking-backend curl -s http://127.0.0.1:3001/health
```

기대: `{"ok":true,"channels":3}` — B가 아직 보고 있으므로 유지.

```bash
curl -s -X POST http://localhost:8080/api/multi-channel-collection/stop/<CH1> -H "X-Client-ID: test-B"
docker exec tongnamuking-backend curl -s http://127.0.0.1:3001/health
```

기대: `{"ok":true,"channels":2}` — 마지막 구독자가 빠져 해제됨.

## 8. 고아 프로세스

```bash
docker-compose down
docker ps -a | grep tongnamuking
```

컨테이너가 통째로 사라지므로 이 환경에서는 고아 프로세스 문제가 드러나지 않는다.
(`bootRun`으로 로컬 개발할 때 나타나는 문제이고, 셧다운 훅 + stdin EOF로 처리해둠.)

## 정리 (검증용 키 삭제)

```bash
docker exec tongnamuking-redis redis-cli --scan --pattern "chat:rank:test-*" | \
  xargs -r docker exec tongnamuking-redis redis-cli DEL
```

---

## 결과 기록

검증이 끝나면 설계 문서
`docs/superpowers/specs/2026-07-17-multichannel-collector-design.md`의
"테스트 > 수동 검증 (필수)" 아래에 **실측값**을 적는다. 예상값이 아니라 실제로 본 숫자를 적을 것.

- 늦은 구독자 0 시작: A의 ZCARD / B의 ZCARD
- daemon.js 프로세스 수: (기대 1)
- `docker stats` 컨테이너 메모리: (리눅스 실측)
- 데몬 강제 종료 후 재구독 + ZCARD 증가 여부

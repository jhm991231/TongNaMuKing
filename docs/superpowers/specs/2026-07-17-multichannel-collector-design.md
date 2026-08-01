# 멀티채널 수집기 데몬화 설계

작성일: 2026-07-17

## 배경

멀티채널 수집은 현재 `(clientId, channelId)` 쌍마다 Node 프로세스를 하나씩 띄운다.
`MultiChannelCollectionService`가 `ProcessBuilder`로 `node index.js <channelId> <clientId>`를
실행하고, `Map<String, Map<String, Process>>`(clientId → channelId → Process)로 관리한다.

이 구조에는 두 가지 문제가 있다.

1. **프로세스 수가 사용자 수에 비례한다.** 사용자당 최대 3개까지 허용하므로
   상한이 `사용자 수 × 3`이다.
2. **같은 채널에 중복 커넥션이 생긴다.** 사용자 10명이 같은 채널을 보면
   치지직 WebSocket 커넥션 10개가 붙어 동일한 채팅을 10번 받는다.

## 목표

- 수집기 프로세스 수를 사용자 수와 무관하게 **1개로 고정**한다.
- 같은 채널에 대한 중복 WebSocket 커넥션을 제거한다.
- 클라이언트별 순위 집계 의미론(구독 시점부터 카운트)을 **현재와 동일하게** 유지한다.

## 비목표

- **독케익 경로는 건드리지 않는다.** `DogCakeCollectionService`도 별도 Node 프로세스를
  띄우지만 항상 1개이므로 사용자 수에 비례하지 않는다. 메모리 문제의 원인이 아니다.
  단, 아래 "공유 자원 주의"에 정리한 대로 현재 두 경로가 코드를 공유하고 있으므로
  분리 방침을 명시한다.
- **Redis 쓰기 증폭은 줄이지 않는다.** 채팅 1건당 `구독자 수 × 2`회 ZINCRBY가 발생하는 것은
  현재와 동일하게 유지한다. 이를 줄이려면 순위 키를 채널 단위로 합치고 클라이언트별
  시작 오프셋을 관리해야 하는데, 전체 순위 키(TTL 48h)와 버킷 키(TTL 2h)의 보관 기간이
  달라 과거 오프셋을 복원할 수 없다. 순위 설계 전면 재검토가 필요하므로 이번 범위에서 제외한다.
- **다중 인스턴스 지원은 하지 않는다.** 인스턴스가 2대가 되면 데몬도 2개가 되어
  중복 커넥션이 재발한다. 리더 선출이 필요한 별도 문제다. 현재 Railway 인스턴스는 1대다.
- **chzzk4j로의 전환은 하지 않는다.** 검토 결과는 아래 "대안 검토" 참고.

## 측정 근거

측정 환경: Windows, `node -e`로 RSS 확인.

| 상태 | RSS |
|---|---|
| 빈 Node 프로세스 | 44 MB |
| chzzk 라이브러리 로드 후 | 49 MB |

axios와 WebSocket 버퍼를 감안하면 수집기 1개당 실사용 50~60MB로 본다.

현재 JVM 설정(`start.sh`)은 `-Xmx192m` + Metaspace 128m + Direct 32m로 약 350MB를 쓴다.
512MB 컨테이너 기준 잔여 메모리는 약 150MB이고, 이는 수집기 3개 분량이다.
과거 OOM Kill 발생 이력과 일치한다.

현재는 Railway 유료 플랜으로 전환해 메모리 여유가 생겼으므로 당장의 장애 상황은 아니다.
그러나 프로세스 수가 사용자 수에 비례하는 구조는 그대로이며, 한도를 올리는 것으로는
비례 관계 자체가 해소되지 않는다. 이 작업의 목적은 **상한을 사용자 수와 분리하는 것**이다.

## 대안 검토

| 방안 | 메모리 | 판단 |
|---|---|---|
| **A. 데몬화 (채택)** | 사용자 수와 무관하게 50MB 고정. 채널 추가 시 WebSocket 1개(~1MB)만 증가 | 채택 |
| B. 채널 단위 프로세스 공유 | 유니크 채널 수 × 50MB | 사용자 20명이 서로 다른 채널 20개를 보면 절약이 0. 목표 미달 |
| C. chzzk4j로 Spring 내재화 | 별도 프로세스 없음. 이론상 최선 | 아래 사유로 보류 |

### C안(chzzk4j) 보류 사유

[R2turnTrue/chzzk4j](https://github.com/R2turnTrue/chzzk4j) (`io.github.r2turntrue:chzzk4j:0.1.6`) 조사 결과:

- 지원 확인: `NormalDonationEvent` / `MissionDonationEvent`, `DonationMessage.getPayAmount()`,
  `ChzzkLiveStatus`의 `categoryType` / `liveCategory` / `liveCategoryValue` / `status` / `chatChannelId`
- **미지원**: `hidden`(블라인드) 플래그 없음. `rawJson`에서 직접 파싱해야 함
- **미검증**: `ChatMessage.getUserId()`가 npm `chzzk`의 `profile.userIdHash`와 동일한 값인지
  소스만으로 확인 불가. 다르면 기존 순위 데이터와 불일치
- README에 "이 라이브러리는 아직 완성되지 않았습니다" 명시. 버전 0.1.6 (npm `chzzk`는 1.10.4)

관리는 활발하다(0.1.6이 2026-07-12 릴리스, 릴리스 16개). 향후 재검토 가치는 있다.

## 공유 자원 주의

"독케익을 건드리지 않는다"는 방침과 충돌하는 지점이 두 곳 있다. 설계 확정 전에 발견했다.

### `chat-collector/index.js`는 두 경로가 공유한다

`DogCakeCollectionService`도 `node index.js <channelId> DOGCAKE_SESSION`으로 같은 파일을
실행하며, `index.js`의 `sendToBackend()`가 `clientId === "DOGCAKE_SESSION"`인지로 분기해
전송 엔드포인트를 고른다(`index.js:132~137`). 카테고리 모니터링도 이 조건으로 켜진다(`index.js:48`).

**방침: `index.js`는 한 줄도 건드리지 않고 독케익 전용으로 남긴다.
멀티채널 데몬은 `chat-collector/daemon.js`로 새로 만든다.**

이벤트 핸들러와 백엔드 전송 부분에서 약 40줄이 중복된다. 공통 모듈로 추출하면
중복은 없앨 수 있으나 `index.js`를 수정해야 하므로 독케익에 회귀 위험이 생긴다.
독케익을 데몬에 통합하는 후속 과제를 수행하면 `index.js`가 삭제되면서 중복도 사라진다.
그때까지의 한시적 중복으로 받아들인다.

### `ChatMessageRequest`도 두 경로가 공유한다

`DogCakeController.addDogCakeMessage()`가 같은 DTO를 쓰며 `request.getClientId()`를
`chatService.addChatMessage()`에 넘긴다(`DogCakeController.java:84~99`).

**방침: DTO에서 `clientId`를 제거하지 않는다.** 독케익이 계속 사용한다.
멀티채널 데몬이 이 필드를 보내지 않고 멀티채널 컨트롤러가 읽지 않을 뿐이다.
멀티채널 경로에서는 항상 null이 된다.

멀티채널 전용 DTO를 새로 만들어 의미를 명확히 할 수도 있으나, 필드 하나를 위해
DTO를 늘리지 않는다. 독케익 통합 시 `clientId`가 자연히 제거된다.

## 아키텍처

핵심 원칙: **수집기는 clientId를 모른다.** 수집기는 "이 채널에 붙어서 채팅을 보고한다"만
수행하고, 누가 구독 중인지는 Spring이 전적으로 안다. 구독자 목록을 양쪽에서 관리하면
반드시 어긋나므로 진실의 원천을 한 곳에 둔다.

```
[프론트] --X-Client-ID--> [Spring]
                            |
                  ChannelSubscriptionRegistry  (진실: channelId → Set<clientId>)
                            |
                  CollectorDaemonManager  --HTTP--> [Node 데몬 1개]
                                                       channelId → ChzzkChat (커넥션 캐시)
                                                              |
                            <---- POST /message/from-collector (clientId 없음)
                            |
                     구독자만큼 팬아웃 → Redis ZINCRBY
```

## 컴포넌트

### 수집기 데몬 (`chat-collector/daemon.js`, 신규)

- CLI 인자를 받지 않는다. 상시 실행된다.
- 내부 상태: `channelId → ChzzkChat` 맵.
- `127.0.0.1`에만 바인딩한다. 포트는 `COLLECTOR_PORT` 환경변수, 기본값 `3001`.
  외부에 노출되지 않으므로 인증은 두지 않는다. 백엔드 주소는 기존과 같이
  `BACKEND_URL` 환경변수를 쓴다(`index.js:12`와 동일).
- HTTP 제어 API:
  - `POST /channels/{channelId}` — 구독. `client.channel(channelId)`로 채널명을 조회하고
    `ChzzkChat`을 연결한 뒤 응답한다(약 1초). **이미 구독 중이면 성공을 반환한다(멱등).**
  - `DELETE /channels/{channelId}` — 해제. **구독 중이 아니어도 성공을 반환한다(멱등).**
  - `GET /health` — 생존 확인.
- 채팅/후원 수신 시 백엔드로 POST한다. **본문에 `clientId`가 없고 `channelId`가 실린다.**
- stdin의 EOF를 감지하면 스스로 종료한다(부모 프로세스 사망 감지).

제어 채널로 stdin 대신 HTTP를 선택한 이유: 구독 성공 여부를 응답으로 받아야 한다.
stdin은 단방향이라 연결 실패를 알 수 없다. 백엔드와 데몬은 같은 컨테이너에 있으므로
(루트 Dockerfile, all-in-one) localhost 고정 포트로 충분하다.

### ChannelSubscriptionRegistry (Spring, 신규)

- 상태: `ConcurrentHashMap<String, Set<String>>` (channelId → clientId 집합). JVM 힙.
- **역방향 맵(clientId → channelId)은 두지 않는다.** 맵이 두 개면 어긋날 수 있다.
  클라이언트 기준 조회(활성 채널 목록, 3개 제한 체크)는 전체를 순회해서 도출한다.
  유니크 채널이 수십 개 규모이므로 비용은 무시할 수 있다. 실측 병목이 확인되면 그때 추가한다.
- `add(channelId, clientId)`는 **"추가"와 "이전에 비어 있었는지 판정"을 원자적으로 수행**하고
  첫 구독자 여부를 반환한다. `ConcurrentHashMap.compute()`를 사용한다.
  단순 `containsKey` 후 `put`으로 구현하면 동시 구독 시 두 클라이언트가 모두
  "내가 첫 구독자"로 판단해 데몬에 구독을 두 번 건다.
- `remove(channelId, clientId)`도 동일하게 원자적으로 수행하고 마지막 구독자 여부를 반환한다.
- 사용자당 최대 3개 제한(`MAX_COLLECTORS_PER_USER`)을 여기로 옮긴다.

### CollectorDaemonManager (Spring, 신규)

- 데몬 프로세스 1개의 생명주기만 담당한다.
- 애플리케이션 시작 시 데몬을 띄운다.
- `process.waitFor()`로 종료를 감지하면 **백오프를 두고 재시작한 뒤, 레지스트리의
  모든 채널을 다시 구독시킨다(replay).** 레지스트리가 Spring 힙에 있으므로 데몬이 죽어도
  복구 재료는 남아 있다. 백오프가 없으면 크래시 루프 시 무한 재시작이 발생한다.
  백오프는 1초에서 시작해 2배씩 늘리고 30초에서 멈춘다. 재시작이 성공해 데몬이
  일정 시간(60초) 살아 있으면 간격을 1초로 되돌린다.
- 셧다운 훅에서 `destroyForcibly()`를 호출한다.
- 제어 API 호출(subscribe/unsubscribe)을 담당한다.

### MultiChannelCollectionService (기존, 대폭 축소)

- `ProcessBuilder` 관련 코드가 전부 제거된다.
- 레지스트리와 데몬 매니저를 조율하는 얇은 층이 된다.
- `clientLastActivity` 맵과 `cleanupInactiveCilents()` 스케줄러는 유지한다.

## 데이터 흐름

### 수집 시작

1. `POST /start/{channelId}` + `X-Client-ID: A`
2. 레지스트리 검증: A가 이미 해당 채널 구독 중인가 / A의 채널이 3개를 채웠는가
3. `registry.add(channelId, A)` → 첫 구독자 여부 반환
4. **첫 구독자일 때만** 데몬에 `POST /channels/{channelId}`
5. **데몬 호출이 실패하면 `registry.remove(channelId, A)`로 롤백한다.**
   롤백하지 않으면 "구독자는 있으나 커넥션은 없는" 유령 상태가 된다.
   이후 B가 같은 채널을 구독해도 첫 구독자가 아니라고 판정되어 데몬을 호출하지 않으므로,
   **A가 빠질 때까지 그 채널의 모든 구독자가 채팅을 받지 못한다.**
   (A가 `/stop`을 호출하거나 비활성 정리에 걸려 빠지면 집합이 비면서 자연히 복구되지만,
   그때까지 조용히 0건이 집계된다.)
   첫 구독자가 아니었던 경우는 데몬을 호출하지 않았으므로 롤백할 것이 없다.

기존 동작 대비 개선점: 현재 코드는 `process.start()` 직후 즉시 `true`를 반환하므로
(`MultiChannelCollectionService.java:65~97`) 연결이 실패해도 사용자에게 "수집 시작됨"으로 표시된다.
데몬은 WebSocket 연결 완료 후 응답하므로 실패를 실제로 전달할 수 있다.

### 채팅 수신

1. 데몬 → `POST /message/from-collector` `{channelId, channelName, username, userId, message, timestamp, type, payAmount?}`
2. 컨트롤러가 `registry.getSubscribers(channelId)` 조회
3. 각 clientId에 대해 `chatRankingService.incrementScore(clientId, channelName, username)`

**팬아웃이 일어나는 지점은 이 루프 한 곳뿐이다.**

`channelName`은 데몬이 구독 시 조회해 채팅 POST에 실어 보낸다.
Redis 키가 `chat:rank:{clientId}:{channelName}` 형태로 channelName 기준이므로
(`ChatRankingService.java:45`) 이 값은 유지되어야 한다.

### 수집 중지 / 정리

- `POST /stop/{channelId}` → `registry.remove` → 구독자가 0이 되면 데몬에 `DELETE /channels/{channelId}`
- `cleanupInactiveCilents()`(30초 주기, 2분 비활성 기준)도 동일 경로를 탄다.
  비활성 클라이언트가 구독한 모든 채널에서 제거하고, 비게 된 채널만 해제한다.

### 순위 집계 의미론 (변경 없음)

Redis 키가 `chat:rank:{clientId}:{channelName}`로 클라이언트마다 분리되어 있으므로,
구독 시점이 곧 집계 시작 시각이 된다.

- **구독 시 Redis를 건드리지 않는다.** 레지스트리에 clientId만 추가한다.
- 키는 구독 이후 첫 채팅이 도착할 때 `ZINCRBY`가 자동 생성한다.
  즉 "0부터 시작"은 별도 초기화 코드 때문이 아니라 **아무것도 하지 않기 때문에** 성립한다.
- A가 10:00, B가 10:30에 구독하면 10:00~10:30 채팅은 B 키를 건드리지 않으므로 포함되지 않는다.

현재 구조(프로세스 N개가 각자 자기 clientId로 POST)와 결과가 동일하다.
**누가 커넥션을 소유하는지만 바뀌고, 무엇이 집계되는지는 바뀌지 않는다.**

주의: 구독 해제 시 Redis 키를 삭제하지 않는다(TTL 48시간). 따라서 A가 수집을 중지했다가
같은 채널을 다시 구독하면 0이 아니라 이전 값에서 이어진다. 이는 현재도 동일한 동작이며
이번 변경으로 달라지지 않는다. "구독 시점 = 시작 시각"은 **최초 구독에 한해** 정확하다.

## 상태 저장

| 상태 | 위치 | 성격 |
|---|---|---|
| `channelId → Set<clientId>` | Spring JVM 힙 | **진실** |
| `clientId → 마지막 활동 시각` | Spring JVM 힙 | 진실 |
| `channelId → ChzzkChat` | Node 데몬 메모리 | 커넥션 캐시. 레지스트리로부터 replay 가능 |
| `chat:rank:{clientId}:{channelName}` | Redis (TTL 48h) | 집계 결과 |

둘이 어긋나면 레지스트리가 이긴다. 데몬 재시작 시 레지스트리를 읽어 재구독한다.

Spring 재시작 시 레지스트리는 소실되며 데몬도 셧다운 훅으로 함께 종료되므로 앞뒤가 맞는다.
Redis 순위 키는 남아 있어 집계 데이터 자체는 유실되지 않는다. 이는 현재와 동일한 동작이다.

레지스트리를 Redis에 두는 방안은 채택하지 않는다. 재시작 복원은 되지만, 실익이 있는
시나리오(다중 인스턴스)에서는 데몬도 인스턴스마다 생겨 중복 커넥션이 재발하므로
문제가 해결되지 않는다.

## 에러 처리

| 상황 | 처리 |
|---|---|
| 데몬 프로세스 사망 | 백오프 후 재시작 + 레지스트리 기준 전체 재구독(replay) |
| 데몬 제어 API 호출 실패 | 사용자에게 실패 반환. 첫 구독자였다면 레지스트리 롤백 |
| WebSocket 끊김 | chzzk 라이브러리가 자동 재연결(현행 유지) |
| 백엔드 다운 중 채팅 POST 실패 | 로그만 남기고 해당 채팅 유실(현행 유지). 순위 서비스이므로 감내 |
| Spring 종료 | 셧다운 훅 + 데몬의 stdin EOF 감지로 고아 프로세스 방지 |

**멱등성이 replay의 전제다.** 이미 구독 중인 채널에 구독 요청이 와도, 구독 중이 아닌 채널에
해제 요청이 와도 성공을 반환해야 재구독이 안전하다.

**고아 프로세스**: `ProcessBuilder`로 띄운 자식은 부모가 죽어도 함께 종료되지 않는다.
현재는 Railway에서 컨테이너째 재시작되어 드러나지 않지만, 데몬은 상시 프로세스이므로
로컬 `bootRun` 재시작 시 Node가 계속 살아남는 문제가 눈에 띄게 된다.

**헬스체크 폴링은 넣지 않는다.** 프로세스는 살아 있으나 HTTP만 응답하지 않는 경우까지
감시할 수 있으나, 드문 케이스에 복잡도를 미리 쓰지 않는다. 프로세스 사망만 처리하고
HTTP 호출 실패는 사용자에게 false를 반환하는 선에서 그친다.

## 테스트

현재 테스트는 `TongnamukingBackendApplicationTests`(컨텍스트 로딩) 1개뿐이다.
전면적인 테스트 스위트 도입은 이번 범위가 아니다.

### 단위 테스트 (신규)

`ChannelSubscriptionRegistry`는 순수 로직이므로 Redis도 Node도 없이 테스트 가능하다.
여기가 틀리면 에러 없이 순위만 조용히 어긋나므로 반드시 커버한다.

- 첫 구독자 판정: 빈 채널에 add하면 true, 두 번째 add는 false
- 마지막 구독자 판정: 2명 중 1명 remove는 false, 마지막 remove는 true
- 사용자당 3개 제한
- 동시 add 시 첫 구독자 판정이 정확히 한 번만 true

### 수동 검증 (필수)

1. **늦은 구독자가 0부터 시작하는가.** 클라이언트 2개로 같은 채널을 시간차를 두고 구독해
   나중에 붙은 쪽의 Redis 키가 0에서 시작하는지 확인한다.
   **이번 변경에서 깨질 수 있는 유일한 의미론이며, 깨져도 에러 없이 숫자만 틀리므로 가장 위험하다.**
2. **메모리가 실제로 줄었는가.** 사용자 3명이 서로 다른 채널을 볼 때 프로세스 수와 RSS 합계를
   변경 전후로 비교한다. 약 150MB → 약 50MB를 기대한다. **줄지 않았다면 이 작업은 실패다.**
3. 데몬을 강제 종료(`kill`)했을 때 재시작 후 모든 채널이 자동 재구독되는가.

## 변경 대상

| 파일 | 변경 |
|---|---|
| `chat-collector/daemon.js` | **신규.** HTTP 제어 API, 채널별 커넥션 맵, clientId 없이 채팅 보고 |
| `chat-collector/index.js` | **변경 없음.** 독케익 전용으로 남는다 |
| `service/ChannelSubscriptionRegistry.java` | 신규 |
| `service/CollectorDaemonManager.java` | 신규 |
| `service/MultiChannelCollectionService.java` | ProcessBuilder 제거, 조율 층으로 축소 |
| `controller/MultiChannelController.java` | `/message/from-collector`에서 구독자 팬아웃 |
| `dto/ChatMessageRequest.java` | **변경 없음.** 독케익이 `clientId`를 계속 사용 |

Dockerfile은 `COPY chat-collector /app/chat-collector`로 디렉터리를 통째로 복사하므로
(루트 `Dockerfile:36`) `daemon.js` 추가만으로 반영된다. Dockerfile 수정은 불필요하다.

데몬은 Spring이 `CollectorDaemonManager`에서 띄우므로 `start.sh`도 수정하지 않는다.

## 후속 과제 (이번 범위 아님)

- 수집기 경로 하드코딩 제거 (`MultiChannelCollectionService.java:57`의 `C:\Users\jhm99\...`).
  데몬 개편 시 경로 지정이 한 곳으로 줄어들어 처리가 쉬워진다.
- 독케익 경로를 데몬에 통합 (상시 50MB 절약 가능하나 장애 범위가 함께 커진다)
- chzzk4j 재검토 (`userId` = `userIdHash` 검증이 선행되어야 함)

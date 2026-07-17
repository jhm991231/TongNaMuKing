# 멀티채널 수집기 데몬화 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `(clientId, channelId)` 쌍마다 Node 프로세스를 띄우던 멀티채널 수집을, Node 데몬 1개 + Spring 측 구독자 레지스트리 구조로 바꿔 프로세스 수 상한을 사용자 수와 분리한다.

**Architecture:** 수집기 데몬은 `clientId`를 모른다. 채널에 붙어 채팅을 보고하기만 하고, 누가 구독 중인지는 Spring의 `ChannelSubscriptionRegistry`(`channelId → Set<clientId>`)가 단독으로 안다. 채팅 1건이 들어오면 컨트롤러가 구독자 수만큼 Redis에 팬아웃한다. 데몬이 죽으면 레지스트리를 재생(replay)해 복구한다.

**Tech Stack:** Node 20 ESM (`node:http` 내장 모듈, chzzk 1.10.4, axios), Spring Boot 3.5.3 / Java 21 (`RestClient`, JUnit 5, AssertJ)

**설계 문서:** `docs/superpowers/specs/2026-07-17-multichannel-collector-design.md`

## Global Constraints

- **독케익 경로는 절대 건드리지 않는다.** `chat-collector/index.js`, `DogCakeCollectionService`, `DogCakeController`, `dto/ChatMessageRequest.java`는 **한 줄도 수정하지 않는다.** `index.js`와 `ChatMessageRequest`는 두 경로가 공유하므로 수정 시 독케익이 깨진다.
- **새 npm 의존성을 추가하지 않는다.** 제어 API는 Node 내장 `node:http`로 만든다. `chat-collector/package.json`의 dependencies는 `chzzk`, `axios` 그대로 둔다.
- `chat-collector`는 ESM이다(`"type": "module"`). `import` 문법을 쓴다.
- Redis 키 형식 `chat:rank:{clientId}:{channelName}`은 바뀌지 않는다. 데몬이 보내는 `channelName`이 기존과 동일한 값이어야 한다.
- 사용자당 최대 채널 수는 3개(기존 `MAX_COLLECTORS_PER_USER` 값 유지).
- 데몬 포트 기본값 `3001`, 환경변수 `COLLECTOR_PORT`. `127.0.0.1`에만 바인딩한다.
- 백엔드 주소 환경변수 `BACKEND_URL`, 기본값 `http://localhost:8080` (기존 `index.js:12`와 동일).
- 재시작 백오프: 1초 시작, 2배씩 증가, 30초 상한. 데몬이 60초 이상 살아 있으면 1초로 리셋.
- 커밋 메시지에 `Co-Authored-By` 트레일러를 넣지 않는다.
- 작업 브랜치는 `feature/collector-daemon`이다. 이미 체크아웃되어 있다.

---

### Task 1: ChannelSubscriptionRegistry

구독자 관리의 진실을 담는 순수 로직. Redis도 Node도 없이 테스트된다. 여기가 틀리면 에러 없이 순위만 조용히 어긋나므로 TDD로 만든다.

**Files:**
- Create: `tongnamuking-backend/src/main/java/com/tongnamuking/tongnamuking_backend/service/ChannelSubscriptionRegistry.java`
- Test: `tongnamuking-backend/src/test/java/com/tongnamuking/tongnamuking_backend/service/ChannelSubscriptionRegistryTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `boolean add(String channelId, String clientId)` — 첫 구독자면 true
  - `boolean remove(String channelId, String clientId)` — 마지막 구독자였으면 true
  - `Set<String> getSubscribers(String channelId)` — 없으면 빈 Set
  - `Set<String> getChannelsOf(String clientId)`
  - `int countChannelsOf(String clientId)`
  - `boolean isSubscribed(String channelId, String clientId)`
  - `Set<String> getAllChannels()`
  - `int getMaxChannelsPerClient()`

- [ ] **Step 1: 실패하는 테스트 작성**

`tongnamuking-backend/src/test/java/com/tongnamuking/tongnamuking_backend/service/ChannelSubscriptionRegistryTest.java`:

```java
package com.tongnamuking.tongnamuking_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelSubscriptionRegistryTest {

    private ChannelSubscriptionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ChannelSubscriptionRegistry();
    }

    @Test
    void 빈_채널에_첫_구독자가_들어오면_true를_반환한다() {
        assertThat(registry.add("channel-1", "client-A")).isTrue();
    }

    @Test
    void 두번째_구독자는_첫_구독자가_아니다() {
        registry.add("channel-1", "client-A");
        assertThat(registry.add("channel-1", "client-B")).isFalse();
    }

    @Test
    void 같은_클라이언트가_중복_구독해도_첫_구독자가_아니다() {
        registry.add("channel-1", "client-A");
        assertThat(registry.add("channel-1", "client-A")).isFalse();
        assertThat(registry.getSubscribers("channel-1")).containsExactly("client-A");
    }

    @Test
    void 구독자가_둘일때_하나를_빼면_마지막이_아니다() {
        registry.add("channel-1", "client-A");
        registry.add("channel-1", "client-B");
        assertThat(registry.remove("channel-1", "client-A")).isFalse();
    }

    @Test
    void 마지막_구독자를_빼면_true를_반환한다() {
        registry.add("channel-1", "client-A");
        assertThat(registry.remove("channel-1", "client-A")).isTrue();
        assertThat(registry.getSubscribers("channel-1")).isEmpty();
    }

    @Test
    void 마지막_구독자가_빠진_채널은_전체_채널_목록에서_사라진다() {
        registry.add("channel-1", "client-A");
        registry.remove("channel-1", "client-A");
        assertThat(registry.getAllChannels()).isEmpty();
    }

    @Test
    void 비어있던_채널에_다시_구독하면_첫_구독자로_판정된다() {
        registry.add("channel-1", "client-A");
        registry.remove("channel-1", "client-A");
        assertThat(registry.add("channel-1", "client-B")).isTrue();
    }

    @Test
    void 구독하지_않은_클라이언트를_빼면_false를_반환한다() {
        registry.add("channel-1", "client-A");
        assertThat(registry.remove("channel-1", "client-Z")).isFalse();
    }

    @Test
    void 존재하지_않는_채널에서_빼도_예외가_나지_않는다() {
        assertThat(registry.remove("nope", "client-A")).isFalse();
    }

    @Test
    void 구독자_조회는_없는_채널에_대해_빈_집합을_준다() {
        assertThat(registry.getSubscribers("nope")).isEmpty();
    }

    @Test
    void 클라이언트가_구독한_채널들을_역방향으로_찾는다() {
        registry.add("channel-1", "client-A");
        registry.add("channel-2", "client-A");
        registry.add("channel-2", "client-B");
        assertThat(registry.getChannelsOf("client-A")).containsExactlyInAnyOrder("channel-1", "channel-2");
        assertThat(registry.getChannelsOf("client-B")).containsExactly("channel-2");
        assertThat(registry.countChannelsOf("client-A")).isEqualTo(2);
        assertThat(registry.countChannelsOf("client-Z")).isZero();
    }

    @Test
    void 구독_여부를_확인한다() {
        registry.add("channel-1", "client-A");
        assertThat(registry.isSubscribed("channel-1", "client-A")).isTrue();
        assertThat(registry.isSubscribed("channel-1", "client-B")).isFalse();
        assertThat(registry.isSubscribed("channel-9", "client-A")).isFalse();
    }

    @Test
    void 사용자당_최대_채널수는_3이다() {
        assertThat(registry.getMaxChannelsPerClient()).isEqualTo(3);
    }

    @Test
    void 반환된_구독자_집합을_수정해도_레지스트리는_영향받지_않는다() {
        registry.add("channel-1", "client-A");
        Set<String> subscribers = registry.getSubscribers("channel-1");
        assertThat(subscribers).isUnmodifiable();
    }

    @Test
    void 동시에_구독해도_첫_구독자_판정은_정확히_한번만_true다() throws Exception {
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger firstCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final String clientId = "client-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (registry.add("channel-1", clientId)) {
                        firstCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(firstCount.get()).isEqualTo(1);
        assertThat(registry.getSubscribers("channel-1")).hasSize(threads);
    }

    @Test
    void 동시에_해제해도_마지막_구독자_판정은_정확히_한번만_true다() throws Exception {
        int threads = 50;
        for (int i = 0; i < threads; i++) {
            registry.add("channel-1", "client-" + i);
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger lastCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final String clientId = "client-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (registry.remove("channel-1", clientId)) {
                        lastCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(lastCount.get()).isEqualTo(1);
        assertThat(registry.getAllChannels()).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
cd tongnamuking-backend && ./gradlew test --tests '*ChannelSubscriptionRegistryTest*' --console=plain
```

Expected: 컴파일 실패. `ChannelSubscriptionRegistry` 클래스가 없다는 오류.

- [ ] **Step 3: 구현**

`tongnamuking-backend/src/main/java/com/tongnamuking/tongnamuking_backend/service/ChannelSubscriptionRegistry.java`:

```java
package com.tongnamuking.tongnamuking_backend.service;

import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 채널별 구독자 레지스트리.
 *
 * 누가 어떤 채널을 수집 중인지에 대한 유일한 진실이다.
 * 수집기 데몬은 clientId를 모르며, 채팅 팬아웃 대상은 전적으로 이 레지스트리가 결정한다.
 *
 * 역방향 맵(clientId -> channelId)은 두지 않는다. 맵이 둘이면 어긋날 수 있고,
 * 유니크 채널이 수십 개 규모이므로 순회 비용은 무시할 수 있다.
 */
@Service
public class ChannelSubscriptionRegistry {

    /** 사용자당 최대 동시 수집 채널 수 */
    private static final int MAX_CHANNELS_PER_CLIENT = 3;

    /** channelId -> clientId 집합. 값은 ConcurrentHashMap.newKeySet()이라 순회가 안전하다. */
    private final Map<String, Set<String>> channelSubscribers = new ConcurrentHashMap<>();

    /**
     * 구독자를 추가하고 첫 구독자였는지 반환한다.
     *
     * "추가"와 "이전에 비어 있었는지 판정"을 compute()로 원자적으로 수행한다.
     * containsKey 후 put으로 구현하면 동시 구독 시 두 클라이언트가 모두
     * 첫 구독자로 판정되어 데몬에 구독을 두 번 건다.
     */
    public boolean add(String channelId, String clientId) {
        boolean[] wasFirst = new boolean[1];
        channelSubscribers.compute(channelId, (key, subscribers) -> {
            if (subscribers == null) {
                wasFirst[0] = true;
                subscribers = ConcurrentHashMap.newKeySet();
            }
            subscribers.add(clientId);
            return subscribers;
        });
        return wasFirst[0];
    }

    /**
     * 구독자를 제거하고 마지막 구독자였는지 반환한다.
     * 집합이 비면 항목 자체를 제거해, 다음 구독이 첫 구독자로 판정되게 한다.
     */
    public boolean remove(String channelId, String clientId) {
        boolean[] wasLast = new boolean[1];
        channelSubscribers.compute(channelId, (key, subscribers) -> {
            if (subscribers == null) {
                return null;
            }
            boolean removed = subscribers.remove(clientId);
            if (removed && subscribers.isEmpty()) {
                wasLast[0] = true;
                return null;
            }
            return subscribers;
        });
        return wasLast[0];
    }

    /** 해당 채널의 구독자들. 팬아웃 대상이다. 없으면 빈 집합. */
    public Set<String> getSubscribers(String channelId) {
        Set<String> subscribers = channelSubscribers.get(channelId);
        return subscribers == null ? Set.of() : Set.copyOf(subscribers);
    }

    /** 해당 클라이언트가 구독 중인 채널들 */
    public Set<String> getChannelsOf(String clientId) {
        Set<String> channels = new HashSet<>();
        channelSubscribers.forEach((channelId, subscribers) -> {
            if (subscribers.contains(clientId)) {
                channels.add(channelId);
            }
        });
        return Set.copyOf(channels);
    }

    public int countChannelsOf(String clientId) {
        return getChannelsOf(clientId).size();
    }

    public boolean isSubscribed(String channelId, String clientId) {
        Set<String> subscribers = channelSubscribers.get(channelId);
        return subscribers != null && subscribers.contains(clientId);
    }

    /** 구독자가 하나 이상인 모든 채널. 데몬 재시작 후 재구독(replay)에 쓴다. */
    public Set<String> getAllChannels() {
        return Set.copyOf(channelSubscribers.keySet());
    }

    public int getMaxChannelsPerClient() {
        return MAX_CHANNELS_PER_CLIENT;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd tongnamuking-backend && ./gradlew test --tests '*ChannelSubscriptionRegistryTest*' --console=plain
```

Expected: PASS. 16개 테스트 모두 통과.

- [ ] **Step 5: 커밋**

```bash
git add tongnamuking-backend/src/main/java/com/tongnamuking/tongnamuking_backend/service/ChannelSubscriptionRegistry.java tongnamuking-backend/src/test/java/com/tongnamuking/tongnamuking_backend/service/ChannelSubscriptionRegistryTest.java
git commit -m "feat: 채널별 구독자 레지스트리 추가

channelId -> Set<clientId> 매핑으로 누가 어떤 채널을 수집 중인지 관리한다.
add/remove가 첫/마지막 구독자 여부를 반환해, 호출측이 데몬 구독/해제 시점을 판단한다.
동시 구독 시 첫 구독자가 중복 판정되지 않도록 compute()로 원자적 처리."
```

---

### Task 2: 수집기 데몬 (daemon.js)

Node 프로세스 1개가 여러 채널을 들고 있다가 채팅을 백엔드로 보고한다. `index.js`는 독케익 전용으로 남기고 새 파일을 만든다.

JS 테스트 인프라가 없고 chzzk 연결은 실제 네트워크에 의존하므로, 이 태스크는 실제 채널로 수동 검증한다. 단위 테스트를 위해 chzzk를 목킹하는 것은 배보다 배꼽이 크다.

**Files:**
- Create: `chat-collector/daemon.js`
- 절대 수정 금지: `chat-collector/index.js`, `chat-collector/package.json`

**Interfaces:**
- Consumes: 없음 (독립 프로세스)
- Produces:
  - `POST /channels/{channelId}` → `200 {ok: true, channelName: string, already: boolean}` / `500 {ok: false, error: string}`
  - `DELETE /channels/{channelId}` → `200 {ok: true, already: boolean}`
  - `GET /health` → `200 {ok: true, channels: number}`
  - 백엔드로 `POST {BACKEND_URL}/api/multi-channel-collection/message/from-collector`
    본문: `{type, channelId, channelName, userId, username, message, timestamp, hidden}` (+ donation이면 `payAmount`).
    **`clientId`는 보내지 않는다.**

- [ ] **Step 1: daemon.js 작성**

`chat-collector/daemon.js`:

```js
import { ChzzkClient } from "chzzk";
import axios from "axios";
import http from "node:http";

const PORT = Number(process.env.COLLECTOR_PORT || 3001);
const BACKEND_URL = process.env.BACKEND_URL || "http://localhost:8080";
const MESSAGE_ENDPOINT = "/api/multi-channel-collection/message/from-collector";

const client = new ChzzkClient();

/** channelId -> { chat, channelName } */
const channels = new Map();

/** 구독 진행 중인 채널. 동시 요청이 커넥션을 두 번 만드는 것을 막는다. */
const pending = new Map();

// ===== 백엔드 전송 =====

async function postToBackend(body) {
  try {
    await axios.post(`${BACKEND_URL}${MESSAGE_ENDPOINT}`, body, {
      headers: { "Content-Type": "application/json" },
    });
  } catch (error) {
    console.error("백엔드 전송 실패:", error.message);
  }
}

function handleChat(channelId, channelName, chat) {
  const message = chat.hidden ? "[블라인드 처리됨]" : chat.message;
  postToBackend({
    type: "chat",
    channelId,
    channelName,
    userId: chat.profile.userIdHash,
    username: chat.profile.nickname,
    message,
    timestamp: new Date().toISOString(),
    hidden: chat.hidden,
  });
}

function handleDonation(channelId, channelName, donation) {
  console.log(`[${channelName}] 후원 ${donation.profile.nickname}: ${donation.payAmount}원`);
  postToBackend({
    type: "donation",
    channelId,
    channelName,
    userId: donation.profile.userIdHash,
    username: donation.profile.nickname,
    message: donation.message,
    timestamp: new Date().toISOString(),
    payAmount: donation.payAmount,
  });
}

// ===== 구독 관리 =====

async function doSubscribe(channelId) {
  const channel = await client.channel(channelId);
  if (!channel) {
    throw new Error(`채널을 찾을 수 없습니다: ${channelId}`);
  }
  const channelName = channel.channelName;

  const chat = client.chat({
    channelId,
    pollInterval: 30 * 1000,
  });

  chat.on("connect", () => console.log(`[${channelName}] 채팅방 연결 성공`));
  chat.on("reconnect", () => console.log(`[${channelName}] 채팅 재연결됨`));
  chat.on("chat", (c) => handleChat(channelId, channelName, c));
  chat.on("donation", (d) => handleDonation(channelId, channelName, d));

  await chat.connect();

  channels.set(channelId, { chat, channelName });
  console.log(`구독 시작: ${channelName} (${channelId}) — 현재 ${channels.size}개 채널`);
  return { ok: true, channelName, already: false };
}

/** 멱등: 이미 구독 중이면 성공을 반환한다. 데몬 재시작 후 replay가 안전하려면 필요하다. */
async function subscribe(channelId) {
  const existing = channels.get(channelId);
  if (existing) {
    return { ok: true, channelName: existing.channelName, already: true };
  }
  if (pending.has(channelId)) {
    return pending.get(channelId);
  }
  const promise = doSubscribe(channelId).finally(() => pending.delete(channelId));
  pending.set(channelId, promise);
  return promise;
}

/** 멱등: 구독 중이 아니어도 성공을 반환한다. */
async function unsubscribe(channelId) {
  const entry = channels.get(channelId);
  if (!entry) {
    return { ok: true, already: true };
  }
  channels.delete(channelId);
  try {
    await entry.chat.disconnect();
  } catch (error) {
    console.error(`[${entry.channelName}] 연결 해제 실패:`, error.message);
  }
  console.log(`구독 해제: ${entry.channelName} (${channelId}) — 현재 ${channels.size}개 채널`);
  return { ok: true, already: false };
}

// ===== HTTP 제어 API =====

const server = http.createServer(async (req, res) => {
  const sendJson = (status, body) => {
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(JSON.stringify(body));
  };

  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);

  if (req.method === "GET" && url.pathname === "/health") {
    sendJson(200, { ok: true, channels: channels.size });
    return;
  }

  const match = url.pathname.match(/^\/channels\/([^/]+)$/);
  if (match) {
    const channelId = decodeURIComponent(match[1]);
    try {
      if (req.method === "POST") {
        sendJson(200, await subscribe(channelId));
        return;
      }
      if (req.method === "DELETE") {
        sendJson(200, await unsubscribe(channelId));
        return;
      }
    } catch (error) {
      console.error(`${req.method} ${url.pathname} 실패:`, error.message);
      sendJson(500, { ok: false, error: error.message });
      return;
    }
  }

  sendJson(404, { ok: false, error: "Not Found" });
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`수집기 데몬 준비 완료 — 127.0.0.1:${PORT}, 백엔드 ${BACKEND_URL}`);
});

// ===== 종료 처리 =====

let shuttingDown = false;

async function shutdown(reason) {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`데몬을 종료합니다 (${reason})`);
  server.close();
  for (const [, entry] of channels) {
    try {
      await entry.chat.disconnect();
    } catch {
      // 종료 중이므로 무시
    }
  }
  channels.clear();
  process.exit(0);
}

// 부모(Spring)가 죽으면 stdin이 EOF가 된다. 고아 프로세스 방지.
process.stdin.resume();
process.stdin.on("end", () => shutdown("부모 프로세스 종료 감지"));

process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));
```

- [ ] **Step 2: 데몬을 직접 띄워 제어 API 확인**

터미널 1에서 데몬 실행:

```bash
cd chat-collector && node daemon.js
```

Expected: `수집기 데몬 준비 완료 — 127.0.0.1:3001, 백엔드 http://localhost:8080`

터미널 2에서 health 확인:

```bash
curl -s http://127.0.0.1:3001/health
```

Expected: `{"ok":true,"channels":0}`

- [ ] **Step 3: 실제 채널 구독 확인**

방송 중인 치지직 채널 ID가 필요하다. `https://chzzk.naver.com/live/<채널ID>` URL의 마지막 부분이 채널 ID다.

```bash
curl -s -X POST http://127.0.0.1:3001/channels/<채널ID>
```

Expected: `{"ok":true,"channelName":"<채널명>","already":false}`

터미널 1 로그에 `[<채널명>] 채팅방 연결 성공`과 `구독 시작: ... — 현재 1개 채널`이 찍힌다.
백엔드가 안 떠 있으므로 `백엔드 전송 실패: connect ECONNREFUSED` 가 반복 출력되는 것이 정상이다. 채팅을 받고 있다는 뜻이다.

- [ ] **Step 4: 멱등성 확인**

같은 채널을 다시 구독:

```bash
curl -s -X POST http://127.0.0.1:3001/channels/<채널ID>
```

Expected: `{"ok":true,"channelName":"<채널명>","already":true}` — 커넥션이 새로 생기지 않는다. 터미널 1에 "구독 시작" 로그가 다시 찍히지 않아야 한다.

구독하지 않은 채널을 해제:

```bash
curl -s -X DELETE http://127.0.0.1:3001/channels/does-not-exist
```

Expected: `{"ok":true,"already":true}`

없는 채널 ID로 구독:

```bash
curl -s -X POST http://127.0.0.1:3001/channels/invalid-channel-id
```

Expected: `500` 과 `{"ok":false,"error":"..."}`. 데몬이 죽지 않고 살아 있어야 한다. 이어서 `curl -s http://127.0.0.1:3001/health` 가 응답하는지 확인한다.

- [ ] **Step 5: 해제와 종료 확인**

```bash
curl -s -X DELETE http://127.0.0.1:3001/channels/<채널ID>
curl -s http://127.0.0.1:3001/health
```

Expected: `{"ok":true,"already":false}` 이후 `{"ok":true,"channels":0}`. 백엔드 전송 실패 로그가 멈춘다.

터미널 1에서 `Ctrl+C`로 종료. `데몬을 종료합니다 (SIGINT)` 출력 후 프로세스가 빠져나간다.

- [ ] **Step 6: 커밋**

```bash
git add chat-collector/daemon.js
git commit -m "feat: 멀티채널 수집기 데몬 추가

프로세스 1개가 여러 채널의 채팅을 수집한다. node:http 내장 모듈로
127.0.0.1에 제어 API를 열고, 구독/해제를 멱등하게 처리한다.
채팅 보고 시 clientId를 보내지 않는다 — 구독자는 백엔드가 안다.
stdin EOF로 부모 종료를 감지해 고아 프로세스를 방지한다.

index.js는 독케익 전용으로 그대로 둔다."
```

---

### Task 3: CollectorDaemonManager

데몬 프로세스 1개의 생명주기와 제어 API 호출을 담당한다.

**Files:**
- Create: `tongnamuking-backend/src/main/java/com/tongnamuking/tongnamuking_backend/service/CollectorDaemonManager.java`
- Modify: `tongnamuking-backend/src/main/resources/application.properties` (끝에 수집기 설정 추가)
- Modify: `tongnamuking-backend/src/main/resources/application-local.properties` (끝에 스크립트 경로 추가)
- Modify: `tongnamuking-backend/src/test/java/com/tongnamuking/tongnamuking_backend/TongnamukingBackendApplicationTests.java`

**주의 — `src/test/resources/application.properties`를 만들지 말 것.** 같은 이름의 파일이
테스트 클래스패스에 있으면 메인의 `application.properties`를 **대체**한다(병합이 아니다).
DB·Redis 설정까지 사라져 컨텍스트 테스트가 깨진다. 테스트에서 데몬을 끄는 것은
`@SpringBootTest(properties = ...)`로 처리한다.

**Interfaces:**
- Consumes: `ChannelSubscriptionRegistry.getAllChannels()` (Task 1)
- Produces:
  - `boolean subscribe(String channelId)` — 데몬에 구독 요청. 성공 여부 반환
  - `void unsubscribe(String channelId)` — 데몬에 해제 요청. 실패해도 예외를 던지지 않는다

**설계 문서와의 차이 (의도적):** 설계 문서는 수집기 경로 하드코딩 제거를 후속 과제로 미뤘으나, 그것은 기존 `MultiChannelCollectionService.java:57`에 대한 것이다. 새로 만드는 코드에까지 `C:\Users\jhm99\...`를 심을 이유는 없으므로 `collector.daemon.script` 프로퍼티로 받는다.

- [ ] **Step 1: 프로퍼티 추가**

`tongnamuking-backend/src/main/resources/application.properties` 맨 끝에 추가:

```properties

# 멀티채널 수집기 데몬 (chat-collector/daemon.js)
# 운영(Docker): Dockerfile이 chat-collector를 /app/chat-collector로 복사한다
collector.daemon.enabled=${COLLECTOR_DAEMON_ENABLED:true}
collector.daemon.script=${COLLECTOR_SCRIPT:/app/chat-collector/daemon.js}
collector.daemon.port=${COLLECTOR_PORT:3001}
```

`tongnamuking-backend/src/main/resources/application-local.properties` 맨 끝에 추가:

```properties

# 멀티채널 수집기 데몬 - 로컬 개발용
# bootRun의 작업 디렉터리가 tongnamuking-backend/ 이므로 상위로 올라간다
collector.daemon.script=../chat-collector/daemon.js
```

`tongnamuking-backend/src/test/java/com/tongnamuking/tongnamuking_backend/TongnamukingBackendApplicationTests.java` 전체를 아래로 교체:

```java
package com.tongnamuking.tongnamuking_backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// 컨텍스트 로딩만으로 node 프로세스가 뜨지 않도록 수집기 데몬을 끈다.
@SpringBootTest(properties = "collector.daemon.enabled=false")
class TongnamukingBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
```

- [ ] **Step 2: 프로퍼티만 추가된 상태에서 기존 테스트 확인 (기준선)**

```bash
cd tongnamuking-backend && ./gradlew test --console=plain
```

Expected: 기존 테스트 전부 PASS. 이 시점에는 아직 매니저가 없으므로 프로퍼티만 추가된 상태다.
`collector.daemon.enabled=false`가 아직 아무 빈에도 안 쓰이지만 무해하다.

- [ ] **Step 3: CollectorDaemonManager 구현**

`tongnamuking-backend/src/main/java/com/tongnamuking/tongnamuking_backend/service/CollectorDaemonManager.java`:

```java
package com.tongnamuking.tongnamuking_backend.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 멀티채널 수집기 데몬(chat-collector/daemon.js) 프로세스 1개의 생명주기 관리.
 *
 * 데몬이 죽으면 백오프 후 재시작하고, 레지스트리에 남아 있는 채널을 전부 다시 구독한다(replay).
 * 레지스트리가 같은 JVM 힙에 있으므로 데몬이 죽어도 복구 재료는 남아 있다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CollectorDaemonManager {

    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 30_000;
    /** 이 시간 이상 살아 있었으면 정상 기동으로 보고 백오프를 리셋한다 */
    private static final long STABLE_UPTIME_MS = 60_000;

    private final ChannelSubscriptionRegistry registry;

    @Value("${collector.daemon.enabled:true}")
    private boolean enabled;

    @Value("${collector.daemon.script}")
    private String scriptPath;

    @Value("${collector.daemon.port:3001}")
    private int port;

    private volatile Process process;
    private volatile boolean shuttingDown = false;
    private volatile long backoffMs = INITIAL_BACKOFF_MS;
    private RestClient restClient;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            log.info("수집기 데몬이 비활성화되어 있습니다 (collector.daemon.enabled=false)");
            return;
        }
        restClient = RestClient.create("http://127.0.0.1:" + port);
        startDaemon();
    }

    @PreDestroy
    public void onShutdown() {
        shuttingDown = true;
        Process current = process;
        if (current != null && current.isAlive()) {
            log.info("수집기 데몬을 종료합니다 (PID: {})", current.pid());
            current.destroy();
            try {
                if (!current.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    current.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
        }
    }

    private synchronized void startDaemon() {
        if (shuttingDown) {
            return;
        }
        try {
            File script = new File(scriptPath);
            ProcessBuilder builder = new ProcessBuilder("node", script.getAbsolutePath());
            builder.redirectErrorStream(true);
            builder.environment().put("COLLECTOR_PORT", String.valueOf(port));

            Process started = builder.start();
            process = started;
            long startedAt = System.currentTimeMillis();
            log.info("수집기 데몬 시작 (PID: {}, script: {})", started.pid(), script.getAbsolutePath());

            pipeLogs(started);
            superviseProcess(started, startedAt);

        } catch (IOException e) {
            log.error("수집기 데몬 시작 실패 (script: {})", scriptPath, e);
            scheduleRestart(System.currentTimeMillis());
        }
    }

    private void pipeLogs(Process target) {
        CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(target.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[collector] {}", line);
                }
            } catch (IOException e) {
                if (!shuttingDown) {
                    log.warn("수집기 데몬 로그 읽기 종료: {}", e.getMessage());
                }
            }
        });
    }

    private void superviseProcess(Process target, long startedAt) {
        CompletableFuture.runAsync(() -> {
            try {
                int exitCode = target.waitFor();
                if (shuttingDown) {
                    log.info("수집기 데몬 종료됨 (exit: {})", exitCode);
                    return;
                }
                log.warn("수집기 데몬이 예기치 않게 종료됨 (exit: {}). 재시작합니다.", exitCode);
                scheduleRestart(startedAt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void scheduleRestart(long previousStartedAt) {
        CompletableFuture.runAsync(() -> {
            try {
                long uptime = System.currentTimeMillis() - previousStartedAt;
                if (uptime > STABLE_UPTIME_MS) {
                    backoffMs = INITIAL_BACKOFF_MS;
                }
                long wait = backoffMs;
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);

                log.info("{}ms 후 수집기 데몬을 재시작합니다", wait);
                Thread.sleep(wait);
                if (shuttingDown) {
                    return;
                }
                startDaemon();
                replaySubscriptions();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** 데몬 재시작 후 레지스트리 기준으로 모든 채널을 다시 구독한다. */
    private void replaySubscriptions() {
        Set<String> channels = registry.getAllChannels();
        if (channels.isEmpty()) {
            return;
        }
        log.info("수집기 데몬 재구독 시작: {}개 채널", channels.size());
        for (String channelId : channels) {
            boolean ok = subscribe(channelId);
            if (!ok) {
                log.error("재구독 실패: {}", channelId);
            }
        }
    }

    /** 데몬에 채널 구독을 요청한다. 데몬이 준비되지 않았거나 실패하면 false. */
    public boolean subscribe(String channelId) {
        if (!enabled) {
            log.warn("수집기 데몬이 비활성 상태입니다. 구독 무시: {}", channelId);
            return false;
        }
        try {
            restClient.post()
                    .uri("/channels/{channelId}", channelId)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.error("데몬 구독 요청 실패: {} — {}", channelId, e.getMessage());
            return false;
        }
    }

    /** 데몬에 채널 해제를 요청한다. 실패해도 예외를 던지지 않는다. */
    public void unsubscribe(String channelId) {
        if (!enabled) {
            return;
        }
        try {
            restClient.delete()
                    .uri("/channels/{channelId}", channelId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("데몬 해제 요청 실패: {} — {}", channelId, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 컴파일과 기존 테스트 확인**

```bash
cd tongnamuking-backend && ./gradlew test --console=plain
```

Expected: PASS. `@SpringBootTest(properties = "collector.daemon.enabled=false")` 덕분에 node 프로세스가 뜨지 않는다.

프로세스가 안 떴는지 확인 (테스트 직후):

```powershell
Get-Process node -ErrorAction SilentlyContinue | Select-Object Id, StartTime
```

Expected: 테스트로 인해 새로 뜬 node 프로세스가 없다. (`StartTime`이 방금 전이 아니어야 한다.)

- [ ] **Step 5: 커밋**

```bash
git add tongnamuking-backend/src/main/java/com/tongnamuking/tongnamuking_backend/service/CollectorDaemonManager.java tongnamuking-backend/src/main/resources/application.properties tongnamuking-backend/src/main/resources/application-local.properties tongnamuking-backend/src/test/java/com/tongnamuking/tongnamuking_backend/TongnamukingBackendApplicationTests.java
git commit -m "feat: 수집기 데몬 생명주기 관리자 추가

앱 기동 시 daemon.js를 띄우고, 죽으면 백오프(1s~30s) 후 재시작한 뒤
레지스트리 기준으로 전체 채널을 재구독한다. 종료 시 destroy로 정리한다.

스크립트 경로는 collector.daemon.script 프로퍼티로 받는다.
테스트에서는 collector.daemon.enabled=false로 데몬을 띄우지 않는다.
src/test/resources/application.properties는 메인 설정을 대체해버리므로 쓰지 않았다."
```

---

### Task 4: 서비스 축소와 컨트롤러 팬아웃

`ProcessBuilder` 코드를 걷어내고 레지스트리/데몬 매니저를 쓰도록 바꾼다. 채팅 수신 시 구독자만큼 Redis에 팬아웃한다.

**Files:**
- Modify (전면 교체): `tongnamuking-backend/src/main/java/com/tongnamuking/tongnamuking_backend/service/MultiChannelCollectionService.java`
- Modify: `tongnamuking-backend/src/main/java/com/tongnamuking/tongnamuking_backend/controller/MultiChannelController.java:116-140` (`addMultiChannelMessage` 메서드만)
- 절대 수정 금지: `dto/ChatMessageRequest.java` (독케익이 `clientId`를 계속 쓴다)

**Interfaces:**
- Consumes:
  - `ChannelSubscriptionRegistry.add/remove/getSubscribers/getChannelsOf/countChannelsOf/isSubscribed/getMaxChannelsPerClient` (Task 1)
  - `CollectorDaemonManager.subscribe/unsubscribe` (Task 3)
  - `ChatRankingService.incrementScore(String clientId, String channelName, String username)` (기존)
- Produces:
  - `MultiChannelCollectionService.startCollection(String clientId, String channelId)` → boolean
  - `MultiChannelCollectionService.stopCollection(String clientId, String channelId)` → boolean
  - 기존 공개 메서드 시그니처 유지: `isCollecting`, `isAnyCollecting`, `getActiveChannels`, `getActiveCollectorCount`, `getMaxCollectors`, `getStatus`, `updateClientActivity`

- [ ] **Step 1: MultiChannelCollectionService 전면 교체**

`tongnamuking-backend/src/main/java/com/tongnamuking/tongnamuking_backend/service/MultiChannelCollectionService.java` 전체를 아래로 교체:

```java
package com.tongnamuking.tongnamuking_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 멀티채널 수집 조율.
 *
 * 프로세스를 직접 띄우지 않는다. 구독자 관리는 ChannelSubscriptionRegistry가,
 * 수집기 데몬 제어는 CollectorDaemonManager가 담당하고 여기서는 둘을 엮기만 한다.
 *
 * 채널의 첫 구독자가 들어올 때만 데몬에 구독을 걸고,
 * 마지막 구독자가 빠질 때만 해제한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MultiChannelCollectionService {

    private final ChannelSubscriptionRegistry registry;
    private final CollectorDaemonManager daemonManager;

    /** 클라이언트별 마지막 활동 시간 (핑 기반) */
    private final Map<String, Long> clientLastActivity = new ConcurrentHashMap<>();

    public boolean startCollection(String clientId, String channelId) {
        if (registry.isSubscribed(channelId, clientId)) {
            log.warn("클라이언트 {}에서 이미 수집 중인 채널입니다: {}", clientId, channelId);
            return false;
        }

        if (registry.countChannelsOf(clientId) >= registry.getMaxChannelsPerClient()) {
            log.warn("클라이언트 {}의 최대 수집기 수({})에 도달했습니다. 현재 수집 중인 채널: {}",
                    clientId, registry.getMaxChannelsPerClient(), registry.getChannelsOf(clientId));
            return false;
        }

        boolean isFirstSubscriber = registry.add(channelId, clientId);
        log.info("멀티채널 수집 시작: {} (클라이언트: {}, 첫 구독자: {})", channelId, clientId, isFirstSubscriber);

        if (!isFirstSubscriber) {
            // 이미 데몬이 해당 채널에 붙어 있다. 팬아웃 대상만 늘어난다.
            clientLastActivity.put(clientId, System.currentTimeMillis());
            return true;
        }

        boolean subscribed = daemonManager.subscribe(channelId);
        if (!subscribed) {
            // 롤백하지 않으면 "구독자는 있으나 커넥션은 없는" 유령 상태가 된다.
            // 이후 다른 클라이언트가 구독해도 첫 구독자가 아니라고 판정되어
            // 데몬을 호출하지 않으므로, 그 채널의 모든 구독자가 조용히 0건을 집계하게 된다.
            registry.remove(channelId, clientId);
            log.error("데몬 구독 실패로 롤백: {} (클라이언트: {})", channelId, clientId);
            return false;
        }

        clientLastActivity.put(clientId, System.currentTimeMillis());
        return true;
    }

    public boolean stopCollection(String clientId, String channelId) {
        if (!registry.isSubscribed(channelId, clientId)) {
            log.warn("클라이언트 {}에서 채널 {}은 수집 중이 아닙니다.", clientId, channelId);
            return false;
        }

        boolean wasLastSubscriber = registry.remove(channelId, clientId);
        if (wasLastSubscriber) {
            daemonManager.unsubscribe(channelId);
            log.info("마지막 구독자 이탈로 채널 해제: {}", channelId);
        }

        log.info("멀티채널 {} 수집 중지됨 (클라이언트: {})", channelId, clientId);
        return true;
    }

    public boolean stopAllCollections(String clientId) {
        for (String channelId : registry.getChannelsOf(clientId)) {
            stopCollection(clientId, channelId);
        }
        return true;
    }

    public boolean isCollecting(String clientId, String channelId) {
        return registry.isSubscribed(channelId, clientId);
    }

    public boolean isAnyCollecting(String clientId) {
        return registry.countChannelsOf(clientId) > 0;
    }

    public Set<String> getActiveChannels(String clientId) {
        return registry.getChannelsOf(clientId);
    }

    public int getActiveCollectorCount(String clientId) {
        return registry.countChannelsOf(clientId);
    }

    public int getMaxCollectors() {
        return registry.getMaxChannelsPerClient();
    }

    public String getStatus(String clientId) {
        Set<String> channels = registry.getChannelsOf(clientId);
        if (channels.isEmpty()) {
            return "수집 중인 채널 없음";
        }
        return String.format("수집 중인 채널: %d/%d - %s",
                channels.size(), registry.getMaxChannelsPerClient(), channels);
    }

    /**
     * 세션 활동 시간 업데이트 (핑 수신시 호출)
     */
    public void updateClientActivity(String clientId) {
        if (registry.countChannelsOf(clientId) > 0) {
            clientLastActivity.put(clientId, System.currentTimeMillis());
            log.debug("클라이언트 활동 업데이트: {}", clientId);
        }
    }

    /**
     * 30초마다 비활성 클라이언트의 구독 정리
     */
    @Scheduled(fixedRate = 30000)
    public void cleanupInactiveCilents() {
        long currentTime = System.currentTimeMillis();
        long inactiveThreshold = 2 * 60 * 1000; // 2분

        clientLastActivity.entrySet().removeIf(entry -> {
            String clientId = entry.getKey();
            long lastActivity = entry.getValue();

            if (currentTime - lastActivity > inactiveThreshold) {
                log.info("비활성 클라이언트 정리: {} ({}분 비활성)", clientId, (currentTime - lastActivity) / 60000);
                stopAllCollections(clientId);
                return true;
            }
            return false;
        });
    }
}
```

- [ ] **Step 2: 컨트롤러 팬아웃으로 교체**

`tongnamuking-backend/src/main/java/com/tongnamuking/tongnamuking_backend/controller/MultiChannelController.java`에서 `addMultiChannelMessage` 메서드(현재 116~140행)를 아래로 교체:

```java
        // chat-collector 데몬이 호출하는 API. 데몬은 clientId를 모르므로 구독자는 여기서 찾는다.
        @PostMapping("/message/from-collector")
        @Operation(summary = "멀티채널 채팅 메시지 수신", description = "멀티채널 수집기 데몬으로부터 채팅 메시지를 수신하여 구독 중인 모든 클라이언트의 Redis 순위에 반영합니다.")
        public ResponseEntity<String> addMultiChannelMessage(@RequestBody ChatMessageRequest request) {
                try {
                        // channelName이 있으면 사용하고, 없으면 channelId 사용
                        String channelName = request.getChannelName() != null ? request.getChannelName()
                                        : request.getChannelId();

                        Set<String> subscribers = channelSubscriptionRegistry
                                        .getSubscribers(request.getChannelId());

                        if (subscribers.isEmpty()) {
                                // 해제 요청과 채팅 수신이 교차한 경우. 버리는 것이 맞다.
                                log.debug("구독자가 없는 채널의 채팅 수신, 무시: {}", channelName);
                                return ResponseEntity.ok("No subscribers");
                        }

                        log.debug("멀티채널 채팅 수신 - 채널: {}, 사용자: {}, 구독자 {}명",
                                        channelName, request.getUsername(), subscribers.size());

                        // 구독 중인 모든 클라이언트의 순위에 반영 (전체 순위 + 1분 버킷)
                        for (String clientId : subscribers) {
                                chatRankingService.incrementScore(
                                                clientId,
                                                channelName,
                                                request.getUsername());
                        }

                        return ResponseEntity.ok("Multi-channel chat message counted in Redis");

                } catch (Exception e) {
                        log.error("멀티채널 채팅 메시지 처리 실패: {}", e.getMessage(), e);
                        return ResponseEntity.internalServerError().body("Failed to process multi-channel chat message");
                }
        }
```

같은 파일에서 의존성 주입 필드에 레지스트리를 추가한다. 27~29행:

```java
        private final MultiChannelCollectionService multiChannelCollectionService;
        private final ChatRankingService chatRankingService;
        private final ClientIdentifierService clientIdentifierService;
        private final ChannelSubscriptionRegistry channelSubscriptionRegistry;
```

import 문에 추가 (5~6행 부근):

```java
import com.tongnamuking.tongnamuking_backend.service.ChannelSubscriptionRegistry;
```

`java.util.Set` import 확인 (18행 `import java.util.Map;` 아래):

```java
import java.util.Set;
```

- [ ] **Step 3: 컴파일과 테스트 확인**

```bash
cd tongnamuking-backend && ./gradlew test --console=plain
```

Expected: PASS. `ChannelSubscriptionRegistryTest` 16개 + 컨텍스트 로딩 테스트.

- [ ] **Step 4: 독케익 경로가 안 건드려졌는지 확인**

```bash
git diff --name-only develop
```

브랜치 전체가 develop 대비 건드린 파일이 나온다(커밋되지 않은 작업 트리 포함).
Expected 목록에 아래가 **없어야** 한다:
- `chat-collector/index.js`
- `tongnamuking-backend/src/main/java/com/tongnamuking/tongnamuking_backend/dto/ChatMessageRequest.java`
- `.../service/DogCakeCollectionService.java`
- `.../controller/DogCakeController.java`

- [ ] **Step 5: 커밋**

```bash
git add tongnamuking-backend/src/main/java/com/tongnamuking/tongnamuking_backend/service/MultiChannelCollectionService.java tongnamuking-backend/src/main/java/com/tongnamuking/tongnamuking_backend/controller/MultiChannelController.java
git commit -m "refactor: 멀티채널 수집을 데몬 + 구독자 레지스트리 구조로 전환

MultiChannelCollectionService에서 ProcessBuilder를 걷어내고
레지스트리/데몬 매니저를 조율하는 얇은 층으로 축소했다.
채널의 첫 구독자일 때만 데몬에 구독을 걸고, 데몬 호출이 실패하면 롤백한다.

컨트롤러는 데몬이 보낸 채팅을 구독자 수만큼 Redis에 팬아웃한다.
데몬이 clientId를 보내지 않으므로 구독자는 레지스트리에서 찾는다."
```

---

### Task 5: 통합 검증

설계 문서의 "수동 검증 (필수)" 3항목을 실제로 확인한다. **이 태스크를 건너뛰면 이 작업이 목적을 달성했는지 알 수 없다.**

**Files:** 없음 (검증만)

**사전 준비:**

```bash
docker-compose up -d mysql redis
cd tongnamuking-backend && ./gradlew bootRun --args='--spring.profiles.active=local'
```

로그에 `수집기 데몬 시작 (PID: ...)` 과 `[collector] 수집기 데몬 준비 완료 — 127.0.0.1:3001` 이 보여야 한다.

방송 중인 채널 ID를 하나 준비한다(`https://chzzk.naver.com/live/<채널ID>`).

- [ ] **Step 1: 늦은 구독자가 0부터 시작하는지 확인 (가장 중요)**

이번 변경에서 깨질 수 있는 유일한 의미론이며, 깨져도 에러 없이 숫자만 틀린다.

클라이언트 A로 수집 시작:

```bash
curl -s -X POST http://localhost:8080/api/multi-channel-collection/start/<채널ID> -H "X-Client-ID: test-A"
```

Expected: `{"success":true,...}`

채팅이 쌓이도록 60초 이상 기다린 뒤, A의 순위가 쌓였는지 확인:

```bash
docker exec -it tongnamuking-redis redis-cli ZREVRANGE "chat:rank:test-A:<채널명>" 0 4 WITHSCORES
```

Expected: 닉네임과 점수가 나온다.

이제 클라이언트 B로 같은 채널 수집 시작:

```bash
curl -s -X POST http://localhost:8080/api/multi-channel-collection/start/<채널ID> -H "X-Client-ID: test-B"
```

**즉시** B의 키를 확인:

```bash
docker exec -it tongnamuking-redis redis-cli EXISTS "chat:rank:test-B:<채널명>"
```

Expected: `0` — 아직 채팅이 안 왔으므로 키 자체가 없다.

30초 뒤 다시 확인:

```bash
docker exec -it tongnamuking-redis redis-cli ZREVRANGE "chat:rank:test-B:<채널명>" 0 4 WITHSCORES
docker exec -it tongnamuking-redis redis-cli ZCARD "chat:rank:test-A:<채널명>"
docker exec -it tongnamuking-redis redis-cli ZCARD "chat:rank:test-B:<채널명>"
```

Expected: **B의 점수 총합이 A보다 현저히 작아야 한다.** B는 구독 시점 이후 채팅만 세고 A는 그 전부터 세고 있었다.
B의 점수가 A와 같다면 팬아웃이 과거 채팅까지 소급했다는 뜻이므로 **실패**다.

- [ ] **Step 2: 프로세스가 1개인지 확인**

세 클라이언트가 서로 다른 채널을 볼 때도 node 프로세스는 1개여야 한다.

```bash
curl -s -X POST http://localhost:8080/api/multi-channel-collection/start/<채널ID2> -H "X-Client-ID: test-C"
curl -s -X POST http://localhost:8080/api/multi-channel-collection/start/<채널ID3> -H "X-Client-ID: test-D"
```

프로세스 수와 메모리 확인:

```powershell
Get-Process node | Select-Object Id, @{N='RSS(MB)';E={[math]::Round($_.WorkingSet64/1MB)}}
```

Expected: **node 프로세스가 정확히 1개.** RSS 50~70MB 수준.
(독케익 수집이 함께 돌고 있다면 2개이며, 그중 하나는 `index.js`다. `Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Select-Object ProcessId, CommandLine` 로 구분한다.)

변경 전이라면 여기서 node 프로세스가 3개(약 150MB)였다. **1개로 줄지 않았다면 이 작업은 실패다.**

- [ ] **Step 3: 중복 채널이 커넥션을 공유하는지 확인**

```bash
curl -s http://127.0.0.1:3001/health
```

Expected: `{"ok":true,"channels":3}` — 클라이언트는 4명(A, B, C, D)인데 A와 B가 같은 채널이므로 채널은 3개다.

- [ ] **Step 4: 데몬을 죽였을 때 자동 복구되는지 확인**

daemon.js 프로세스만 골라서 죽인다(독케익의 index.js는 건드리지 않는다):

```powershell
Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
  Where-Object { $_.CommandLine -like '*daemon.js*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
```

Spring 로그를 본다.

Expected 순서:
1. `수집기 데몬이 예기치 않게 종료됨 (exit: 1). 재시작합니다.`
2. `1000ms 후 수집기 데몬을 재시작합니다`
3. `수집기 데몬 시작 (PID: ...)`
4. `수집기 데몬 재구독 시작: 3개 채널`
5. `[collector] 구독 시작: ...` × 3

재구독 확인:

```bash
curl -s http://127.0.0.1:3001/health
```

Expected: `{"ok":true,"channels":3}`

- [ ] **Step 5: 마지막 구독자가 빠지면 커넥션이 해제되는지 확인**

```bash
curl -s -X POST http://localhost:8080/api/multi-channel-collection/stop/<채널ID> -H "X-Client-ID: test-A"
curl -s http://127.0.0.1:3001/health
```

Expected: `{"ok":true,"channels":3}` — B가 아직 같은 채널을 보고 있으므로 커넥션이 유지된다.

```bash
curl -s -X POST http://localhost:8080/api/multi-channel-collection/stop/<채널ID> -H "X-Client-ID: test-B"
curl -s http://127.0.0.1:3001/health
```

Expected: `{"ok":true,"channels":2}` — 마지막 구독자가 빠져 커넥션이 해제됐다.

- [ ] **Step 6: 고아 프로세스가 안 남는지 확인**

`bootRun`을 `Ctrl+C`로 종료한 뒤:

```powershell
Get-Process node -ErrorAction SilentlyContinue
```

Expected: daemon.js 프로세스가 없다. (독케익 `index.js`는 별개다.)

- [ ] **Step 7: 검증 결과를 설계 문서에 기록하고 커밋**

`docs/superpowers/specs/2026-07-17-multichannel-collector-design.md`의 "테스트 > 수동 검증 (필수)" 섹션 아래에 실측 결과를 추가한다. 실제로 측정한 값을 적는다 — 예상값을 적지 않는다.

```markdown
### 검증 결과 (YYYY-MM-DD 실측)

- 늦은 구독자 0 시작: (결과 기록)
- node 프로세스 수: 변경 전 N개 / 변경 후 N개
- RSS 합계: 변경 전 N MB / 변경 후 N MB
- 데몬 강제 종료 후 재구독: (결과 기록)
```

```bash
git add docs/superpowers/specs/2026-07-17-multichannel-collector-design.md
git commit -m "docs: 수집기 데몬화 실측 검증 결과 기록"
```

---

## 완료 후

`superpowers:finishing-a-development-branch` 스킬로 develop 머지 여부를 결정한다.
HANDOFF.md의 브랜치 흐름은 피처 → develop → deploy이며, deploy push가 Railway 자동배포를 트리거한다.

**배포 시 확인:** 운영에서는 `collector.daemon.script`가 기본값 `/app/chat-collector/daemon.js`로 해석된다. 루트 `Dockerfile:36`의 `COPY chat-collector /app/chat-collector`가 `daemon.js`를 포함하므로 Dockerfile 수정은 불필요하다.

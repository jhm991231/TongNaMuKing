# 수집기 스크립트 경로 트러블슈팅

- 작성일: 2026-08-03
- 대상: `CollectorDaemonManager`(멀티채널 데몬), `DogCakeCollectionService`(독케익 전용)
- 한 줄 요약: 자바가 Node 스크립트를 자식 프로세스로 띄우는데, 그 스크립트 경로가 실행
  환경마다 달라서 생기는 문제들과 그 대응

## 1. 증상

수집기가 뜨지 않는다. 로그에 다음 중 하나가 보인다.

```
수집기 데몬 스크립트를 찾을 수 없습니다. 설정=..., 찾은 경로=..., 작업 디렉터리=...
수집기 데몬 시작 실패 (설정=..., 찾은 경로=...)
수집기 데몬이 비활성화되어 있습니다 (collector.daemon.enabled=false)
Error: Cannot find module '...'          ← Node 가 뱉는 것
```

마지막 것은 두 가지를 뜻할 수 있어 헷갈린다. 뒤에 붙은 이름으로 구분한다.

| 메시지 | 뜻 |
|---|---|
| `Cannot find module '...\daemon.js'` | 스크립트 파일 자체가 없음 → 경로 문제 |
| `Cannot find module 'chzzk'` | 의존성 미설치 → `chat-collector`에서 `npm install` |

## 2. 빠른 진단

1. **프로파일이 켜졌나** — 기동 로그 맨 앞을 본다.
   ```
   The following 1 profile is active: "local"     ← 켜짐
   No active profile set, falling back to ...     ← 안 켜짐. 컨테이너용 기본값이 쓰인다
   ```
2. **작업 디렉터리가 맞나** — 실패 로그의 `작업 디렉터리=` 를 본다.
   로컬은 `tongnamuking-backend/` 여야 상대 경로 `../chat-collector/...` 가 맞게 풀린다.
   IntelliJ `Run` → `Edit Configurations` → `Working directory` 확인.
3. **node 를 찾나** — `Cannot run program "node"` 면 PATH 문제다. IntelliJ 를 켠 뒤에
   Node 를 설치했다면 IntelliJ 를 완전히 재시작한다.
4. **의존성이 있나** — `chat-collector/node_modules` 존재 확인.

## 3. 왜 이 문제가 생기는가

같은 코드가 세 환경에서 도는데 경로 조건이 전부 다르다.

| | 실행 주체 | 스크립트 위치 | JVM 작업 디렉터리 |
|---|---|---|---|
| IntelliJ / `bootRun` | 호스트 | `<저장소>/chat-collector/` | 실행 구성에 따라 다름 |
| Docker (compose) | 컨테이너 | `/app/chat-collector/` (마운트) | `/app` |
| Railway | 컨테이너 | `/app/chat-collector/` (`COPY`) | `/app` |

Docker 에서 `/app/chat-collector` 가 마운트인 이유는 compose 의 빌드 컨텍스트가
`./tongnamuking-backend` 라서 그 바깥의 `chat-collector` 를 `COPY` 할 수 없기 때문이다.
Railway 는 컨텍스트가 저장소 루트라 `COPY` 로 구워 넣는다.

## 4. 설정이 덮어써지는 두 층

```
application.properties            ← 항상 로드. ${환경변수:기본값} 형태
    ↑ 덮어씀
application-local.properties      ← local 프로파일일 때만
```

- **컨테이너**는 환경변수로 덮어쓴다. 프로파일을 쓰지 않는다.
- **로컬**은 `local` 프로파일로 덮어쓴다.

우선순위는 커맨드라인 > 시스템 프로퍼티 > **환경변수** > 프로파일 파일 > 기본 파일.

> **`spring.profiles.active=local` 을 `application.properties` 에 기본값으로 넣지 말 것.**
> `application-local.properties` 의 DB URL 은 `${}` 없이 하드코딩(`localhost:3307`)이라,
> 컨테이너에서 이 프로파일이 켜지면 `DATABASE_URL` 환경변수를 무시하고 컨테이너 안에서
> `localhost` 를 찾다가 실패한다. 프로파일은 IntelliJ 실행 구성이나 `bootRun` 태스크에서만
> 지정한다.

## 5. 실제로 있었던 두 사건

### 5.1 개인 PC 절대 경로 하드코딩 (2026-08-03 수정)

`DogCakeCollectionService` 가 OS 를 감지해 경로를 코드에 박아두고 있었다.

```java
String scriptPath = os.contains("win") ?
    "C:\\Users\\jhm99\\vscode_workspace\\TongNaMuKing\\chat-collector\\index.js" :
    "/app/chat-collector/index.js";
```

다른 사람은 물론 다른 노트북에서도 동작하지 않았다. `CollectorDaemonManager` 는 이미
프로퍼티로 빼는 방식이었으므로 거기에 맞췄다.

### 5.2 IntelliJ 프로젝트 루트 변경으로 상대 경로가 깨짐

루트 폴더의 파일(compose, `daemon.js` 등)을 편집하려고 IntelliJ 프로젝트를 저장소
루트로 열었더니 작업 디렉터리가 바뀌었다.

```
작업 디렉터리 = tongnamuking-backend/  →  ../chat-collector/  = <저장소>/chat-collector  ✅
작업 디렉터리 = <저장소> 루트           →  ../chat-collector/  = 저장소 밖               ❌
```

Node 는 `Cannot find module '...daemon.js'` 만 뱉어서 원인 파악에 시간이 걸렸다.
이 사건이 §7 의 대응으로 이어졌다.

## 6. 현재 구조

```properties
# application.properties — 기본값은 컨테이너 경로
collector.daemon.script=${COLLECTOR_SCRIPT:/app/chat-collector/daemon.js}
dogcake.collector.script=${DOGCAKE_SCRIPT:/app/chat-collector/index.js}

# application-local.properties — 작업 디렉터리가 tongnamuking-backend/ 라는 전제
collector.daemon.script=../chat-collector/daemon.js
dogcake.collector.script=../chat-collector/index.js
```

두 수집기가 같은 패턴을 쓴다. 코드 쪽도 동일하다.

```java
File script = new File(scriptPath).getAbsoluteFile();   // ① JVM 작업 디렉터리 기준으로 고정
if (!script.isFile()) { /* 설정·절대경로·cwd 를 함께 알린다 */ }   // ② 사전 검증
new ProcessBuilder("node", script.getAbsolutePath(), ...);        // ③
processBuilder.directory(script.getParentFile());                 // ④ 실행 위치 고정
```

- **①** `getAbsoluteFile()` 은 `getParentFile()`(④)도 절대가 되게 하려는 것이다.
  `getAbsolutePath()` 는 상대 `File` 에서도 절대를 돌려주므로 ③만 보면 필요 없다.
- **④** `directory()` 를 지정하지 않으면 자식은 **JVM 의 작업 디렉터리**를 물려받는다
  (스크립트 위치가 아니다). 환경마다 달라지므로 스크립트 폴더로 고정한다.

`ProcessBuilder` 는 `new` 시점에 아무 일도 하지 않고 `start()` 가 프로세스를 만든다.
그래서 조립 부분(`buildCollectorProcess`)을 분리해 두면 node 없이도 단위 테스트가 된다.

## 7. 왜 "고치지 않고 시끄럽게" 했는가

상대 경로는 정의상 작업 디렉터리에 의존한다. 세 가지 안을 검토했다.

| 안 | 내용 | 결과 |
|---|---|---|
| A | 실패 시 설정값·절대경로·cwd 를 함께 로그 | **채택** |
| B | 프로젝트 루트를 코드가 탐색해 cwd 의존 제거 | 보류 |
| C | 기본값을 없애 설정을 필수화 | 기각 |

**A 를 고른 이유.** 흔들리는 것은 로컬 개발 환경 하나뿐이다. Docker 와 Railway 는 절대
경로라 애초에 영향이 없다. B 는 운영 코드에 개발 편의를 위한 탐색 로직을 넣게 되는데,
이득 보는 상황이 좁은 데 비해 비용이 있다. 이런 종류의 문제는 "안 나게 하기"보다
"났을 때 즉시 알기"가 비용 대비 효과가 크다.

데몬 쪽은 스크립트가 없을 때 **재시작 스케줄을 걸지 않는다.** `scheduleRestart()` 는
"데몬이 죽었으니 다시 띄우자"는 복구 루프인데, 설정 오류는 재시도해도 낫지 않고
같은 에러만 쌓이기 때문이다.

## 8. 알려진 취약점

- **상대 경로는 여전히 작업 디렉터리에 의존한다.** IntelliJ 실행 구성의
  `Working directory` 를 `tongnamuking-backend` 로 고정해 두어야 한다. 이 전제가 코드가
  아니라 IDE 설정에 있다는 것이 이 구조의 약한 고리다.
- **테스트는 `DogCakeCollectionService` 쪽만 있다.** `CollectorDaemonManager` 는
  `startDaemon()` 이 private 이고 재시작 스케줄러·프로세스 감시와 얽혀 있어 테스트를
  붙이려면 클래스를 분해해야 한다.
- **`node_modules` 는 컨테이너가 설치한 것을 호스트가 공유한다.** compose 가
  `./chat-collector` 를 바인드 마운트하고 컨테이너 시작 시 `npm install` 을 실행하므로,
  결과가 호스트 디스크에 남는다. `chzzk`·`axios` 가 순수 자바스크립트라 가능한 것이고,
  네이티브 확장을 쓰는 의존성이 추가되면 이 공유는 깨진다.

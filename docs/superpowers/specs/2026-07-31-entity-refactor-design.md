# 엔티티 리팩터링 설계 — `@Data` 제거와 도메인 메서드 추출

- 작성일: 2026-07-31
- 대상: `tongnamuking-backend` 엔티티 4개와 이를 쓰는 서비스 3개
- 브랜치: `refactor/entity` (`develop`에서 분기)

## 1. 배경과 목적

엔티티 4개가 모두 `@Data @NoArgsConstructor @AllArgsConstructor` 조합이다. 쓰기는
편하지만 JPA 엔티티에서는 각각 알려진 문제를 만든다. 이 프로젝트는 포트폴리오에서
비중이 큰 만큼, 엔티티 설계를 정리해 두는 편이 값이 있다.

목적은 두 가지다.

1. `@Data`가 켜는 기능들(특히 `@ToString`, `@EqualsAndHashCode`, `@Setter`)을 걷어내
   엔티티를 안전하게 만든다.
2. `ChzzkService`에 흩어진 방송 시작/종료 규칙을 `Channel`로 옮겨, 스프링·DB·외부 API
   없이 단위 테스트할 수 있게 한다.

## 2. 현재 상태

**엔티티 4개** — `User`, `Channel`, `ChatMessage`, `CategoryChangeEvent`.
전부 `@Data @NoArgsConstructor @AllArgsConstructor`.

**세터 호출 21곳**, 서비스 3개에 몰려 있고 성격이 둘로 갈린다.

| 성격 | 개수 | 위치 |
|---|---|---|
| 생성용 (`new` 후 세터로 채우기) | 18 | `ChatService:29-52`, `CategoryService:31-63` |
| 변경용 (기존 엔티티 상태 전이) | 3 | `ChzzkService:105,118,124` |

**테스트** — `ChatStatsServiceTest`(아직 커밋되지 않은 상태)와 기본
`TongnamukingBackendApplicationTests` 둘뿐이다. 안전망이 얇다는 점을 전제로 작업 순서를
잡는다(§7).

## 3. 문제

### 3.1 `@Data`가 엔티티에서 만드는 문제

위험도 순.

- **`@ToString`** — `ChatMessage`와 `CategoryChangeEvent`는 `@ManyToOne(LAZY)` 연관을
  갖는다. 자동 생성된 `toString()`이 이들을 출력하려 하므로, 트랜잭션 안에서는 불필요한
  추가 쿼리가 나가고 밖에서는 `LazyInitializationException`이 난다. 현재는 역방향 필드가
  없어 무한 재귀까지 가지는 않지만, `Channel`에 `List<ChatMessage>`를 추가하는 순간
  조건이 갖춰진다.
- **`@EqualsAndHashCode`** — 모든 필드로 계산한다. 엔티티는 저장 전 `id`가 null이고
  저장 후 값이 생기므로 `hashCode`가 도중에 바뀐다. `HashSet`에 담아둔 엔티티를
  저장 후 찾지 못하는 종류의 사고로 이어진다.
- **`@Setter`** — 함께 바뀌어야 할 필드를 따로 바꿀 수 있다. `Channel`의
  `isCurrentlyLive`/`liveStartTime`/`lastLiveEndTime`이 정확히 그런 관계다.
- **`@AllArgsConstructor`** — 위치 인자다. `ChatMessage(id, user, channel, message,
  clientId, timestamp)`에서 `message`와 `clientId`가 둘 다 String이고 인접해 있어,
  순서를 바꿔 넣어도 컴파일된다.

### 3.2 도메인 로직이 서비스에 있다

`ChzzkService:99-130`이 방송 상태 전이를 직접 수행한다. "이전 종료 후 30분 이내면 연속
방송으로 보고 `liveStartTime`을 유지한다"는 규칙이 로깅 사이에 끼어 있고, 이 규칙만
검증하려 해도 치지직 API mock과 스프링 컨텍스트가 필요해 사실상 테스트되지 않는다.

## 4. 설계

### 4.1 공통 — 롬복 조합 교체

모든 엔티티에서 아래로 바꾼다.

```
@Data @NoArgsConstructor @AllArgsConstructor
  ↓
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED) + private 생성자에 @Builder
```

- `PROTECTED` 기본 생성자: JPA는 리플렉션으로 필요로 하지만 애플리케이션 코드가
  빈 엔티티를 만드는 것은 막는다.
- private 생성자 + `@Builder`: 생성 경로를 하나로 모으고, 인자를 이름으로 지정하게 한다.
- `@Setter` 없음: 상태 변경은 의미가 드러나는 메서드로만 한다.

### 4.2 `Channel` — 방송 상태 전이를 엔티티가 책임진다

```java
// 이전 방송 종료 후 이 시간 안에 다시 켜지면 같은 방송의 연장으로 본다
private static final int CONTINUOUS_BROADCAST_MINUTES = 30;

public boolean isLive() {
    return Boolean.TRUE.equals(isCurrentlyLive);   // null 방어를 엔티티가 흡수
}

public void startLive(LocalDateTime now) {
    this.isCurrentlyLive = true;
    if (!isContinuationOf(now)) {
        this.liveStartTime = now;                  // 새 방송 → 시작 시각 갱신
    }
    // 연속 방송이면 liveStartTime을 건드리지 않는다
}

public void endLive(LocalDateTime now) {
    this.isCurrentlyLive = false;
    this.lastLiveEndTime = now;
    // liveStartTime은 유지 (마지막 방송 시작 시각 기록용)
}

private boolean isContinuationOf(LocalDateTime now) {
    if (lastLiveEndTime == null) {
        return false;                              // 종료 기록이 없으면 첫 방송
    }
    return Duration.between(lastLiveEndTime, now).toMinutes() <= CONTINUOUS_BROADCAST_MINUTES;
}
```

현재 시각을 파라미터로 받는다. 내부에서 `LocalDateTime.now()`를 부르면 30분 경계를
테스트할 방법이 없어진다.

빌더는 `channelName`, `description`, `chzzkChannelId`만 받고 `isCurrentlyLive`는 `false`로
초기화한다. 방송 상태 필드는 위 메서드로만 바뀐다.

### 4.3 나머지 엔티티 3개 — 빌더 전환만

| 엔티티 | 빌더 파라미터 | 비고 |
|---|---|---|
| `User` | `username`, `profileImageUrl` | |
| `ChatMessage` | `user`, `channel`, `message`, `clientId`, `timestamp` | |
| `CategoryChangeEvent` | `channel`, 이전/새 카테고리 6개, `changeDetectedAt` | `@PrePersist` 유지 |

`CategoryService:43-54`의 조건부 세팅은 빌더에서도 그대로 표현된다 — 해당 카테고리가
null이면 그 필드를 넣지 않으면 된다.

### 4.4 `ChzzkService` 변경

```java
boolean nowLive = channelInfo.isOpenLive();

if (nowLive != channel.isLive()) {
    LocalDateTime now = LocalDateTime.now();
    if (nowLive) {
        channel.startLive(now);
        log.info("독케익 라이브 시작 감지: {}", now);
    } else {
        channel.endLive(now);
        log.info("독케익 라이브 종료 감지: {}", now);
    }
    channelRepository.save(channel);
}
```

서비스에 남는 책임은 넷이다 — 치지직 API 호출, 변화 감지, 분기, 저장.

`wasLive` 지역 변수와 `isCurrentlyLive && !wasLive` 형태의 조건은 삭제한다. 바깥
`if`에서 두 값이 다르다는 것이 이미 판명되므로 항상 참인 조건이다. 32줄이 19줄이 된다.

### 4.5 `ChatStatsService` — 같은 null 방어 두 곳 정리

`getIsCurrentlyLive() != null && ...` 형태의 방어가 `ChatStatsService:362,384`에도 있다.
`isLive()`가 그 역할을 대신하므로 함께 정리한다. (작업 중 발견해 범위에 추가했다.)

- **362행** `!= null && getIsCurrentlyLive()` → `isLive()`.
  둘 다 "명시적으로 true일 때만 참"이라 의미가 완전히 같다.
- **384행** `!= null && !getIsCurrentlyLive()` → `!isLive()`.
  **null 처리가 달라진다.** 기존 코드에서 null은 조건을 통과하지 못해 "방송 중" 분기로
  흘러가 30분 버퍼가 붙지 않았다. `isLive()`는 null을 false로 뭉개므로 `!isLive()`는
  참이 되어 버퍼가 붙는다. 최종적으로 조회 기준 시각이 자정에서 방송 시작 시각으로
  바뀌므로 통계 값이 달라질 수 있는 변경이다.
  `channels.is_currently_live`가 NULL인 행이 없음을 확인한 뒤 바꿨다(2026-08-01).

## 5. 결정 사항

| 항목 | 결정 | 근거 |
|---|---|---|
| 30분 경계 판정 | `.toMinutes() <= 30` **그대로 유지** | 리팩터링은 동작을 바꾸지 않는다. 현재 구현은 잘라내기 때문에 실제로는 "30분 59초까지 연속"으로 동작하는데, 이를 `Duration.compareTo`로 엄밀히 바꾸는 것은 **동작 변경**이므로 별도 커밋으로 분리한다 |
| 로그 | "연속 방송 감지"/"새 방송 시작" 구분 로그를 시작/종료 로그로 단순화 | 엔티티에서 로깅하지 않기 위함. 연속 여부를 로그로 남기려면 `isContinuationOf`를 공개해야 하는데, 그 대가가 더 크다 |
| 현재 시각 | 메서드 파라미터로 주입 | 30분 경계를 테스트하기 위해 |
| DTO의 `@Data` | 유지 | DTO는 영속성 생명주기·지연 로딩·식별자가 없어 문제가 생기지 않는다 |
| 브랜치 | `refactor/entity` | 엔티티 4개와 서비스 3개를 동시에 건드리므로, 실패 시 브랜치를 버릴 수 있게 한다 |
| 테스트에서 `id` 지정 | `ReflectionTestUtils.setField` | `ChatStatsServiceTest` 의 목 스터빙이 채널 id 를 키로 쓰므로 id 가 필요하다. 빌더에 `id` 를 추가하면 운영 코드에서도 지정할 수 있게 되는데, id 가 채워진 엔티티를 `save()` 하면 Spring Data JPA 가 INSERT 대신 `merge()`(SELECT 후 UPDATE)로 동작한다. 운영 코드의 제약을 유지하고 테스트에서만 리플렉션으로 우회한다 |

## 6. 테스트 전략

새로 추가하는 테스트는 `ChannelTest` 하나다. 스프링도 DB도 외부 API도 없는 순수 POJO
테스트다.

| 케이스 | 기대 |
|---|---|
| 첫 방송 시작 | `isLive()` true, `liveStartTime`이 주어진 시각 |
| 종료 | `isLive()` false, `lastLiveEndTime` 기록, `liveStartTime` 유지 |
| 종료 후 10분 뒤 재시작 | `liveStartTime` **유지** (연속 방송) |
| 종료 후 40분 뒤 재시작 | `liveStartTime` **갱신** (새 방송) |

경계값(30분 정각, 30분 59초)은 §5의 결정에 따라 현재 동작을 그대로 고정한다.

나머지 변경(세터 → 빌더)은 별도 테스트를 두지 않는다. 세터가 사라지면 호출부가
컴파일 에러가 나므로 컴파일러가 누락을 100% 잡아낸다. 조용히 잘못될 여지가 없다.

## 7. 작업 순서

안전망이 얇으므로(§2) 도메인 로직을 먼저 테스트로 고정한 뒤 껍데기를 바꾼다.

- [x] 1. `refactor/entity` 브랜치 생성
- [x] 2. `ChannelTest` 작성 → 실패 확인 → `Channel`에 도메인 메서드 추가 → 통과
  *이 단계에서는 `@Data`가 아직 살아 있어 기존 코드가 깨지지 않는다*
- [x] 3. `ChzzkService`를 새 메서드를 쓰도록 변경 (작업 중 `ChatStatsService`도 포함 — §4.5)
- [x] 4. 엔티티 4개에서 `@Data` 제거, `@Getter`/`@Builder`로 교체
- [x] 5. 깨진 세터 호출부를 빌더로 전환
  *`src/main` 21곳 외에 `ChatStatsServiceTest` 4곳이 더 있었다. 최종적으로 프로젝트 전체 세터 호출 0개*
- [x] 6. 전체 빌드 + 테스트 확인 — 15개 통과 (2026-08-01)

2번을 먼저 하는 것이 핵심이다. 5번에서 무언가 깨져도 2번의 테스트가 방송 상태 규칙을
지켜준다. 순서를 뒤집으면 안전망 없이 큰 수술을 하게 된다.

## 8. 범위에서 제외

작업 중 눈에 띄었으나 이번 목적과 직접 상관없어 다루지 않는다.

- `ChatService:40-41`의 하드코딩된 채널 ID (`"독케익"` → `b68af124...`)
- `ChatService`와 `CategoryService`에 중복된 "채널을 찾거나 생성" 로직
- 30분 경계의 잘라내기 동작 자체 (§5 참고 — 고치려면 별도 커밋)
- DTO 클래스들의 `@Data`

# 🪵 TongNaMuKing (채팅 통나무)

치지직 실시간 채팅 수집 및 통계 분석 시스템

## 📋 프로젝트 개요

TongNaMuKing은 치지직(Chzzk) 스트리밍 플랫폼의 실시간 채팅을 수집하고 분석하는 웹 애플리케이션입니다.

### 주요 기능

- 🔴 **실시간 채팅 수집**: 치지직 채널의 실시간 채팅 메시지 수집
- 📊 **실시간 채팅 순위**: Redis Sorted Set 기반 사용자별 채팅 순위 (서버 재시작에도 유지)
- ⏱️ **시간범위 순위**: 최근 5분/10분/30분/1시간 순위 조회 (1분 버킷 합산)
- 🐕 **독케익 전용 기능**: 독케익 채널 전용 수집 및 저챗견 비율 분석
- 🔀 **멀티채널 지원**: 최대 3개 채널 동시 수집
- 🔍 **채널 검색**: 치지직 플랫폼의 모든 채널 검색 및 수집

## 🏗️ 시스템 아키텍처

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   React         │    │   Spring Boot    │◄──►│     MySQL       │
│   Frontend      │◄──►│   Backend        │    │  (채팅 원본 보관, │
│   (Port 5173)   │    │   (Port 8080)    │    │   독케익 분석)    │
└─────────────────┘    └────────┬─────────┘    └─────────────────┘
                                │       ▲
                                │       └─────►┌─────────────────┐
                                ▼              │     Redis       │
                       ┌──────────────────┐    │ (실시간 채팅 순위, │
                       │   Node.js        │    │   Sorted Set)   │
                       │   Chat Collector │    └─────────────────┘
                       │   (치지직 API)    │
                       └──────────────────┘
```

- **MySQL**: 독케익 채널의 채팅 원본 영구 보관 및 저챗견 비율 분석
- **Redis**: 멀티채널 실시간 순위 저장소 — 채팅 유입 시 증분 갱신(ZINCRBY), 조회는 정렬 유지 구조에서 즉시 반환

## 🔍 핵심 문제 해결 (트러블슈팅)

이 프로젝트에서 겪은 문제들과, 증상이 아니라 잘못된 전제를 찾아 해결한 과정을 정리했습니다.

### 1. 힙 메모리 OOM → 데이터 모델 재설계

- **문제:** 배포 후 서버가 반복적으로 OOM으로 종료됨.
- **원인:** 멀티채널 기능이 수집한 채팅을 `Map<clientId, Map<channel, List<ChatData>>>` 형태로, 채팅 친 사람·원문·타임스탬프까지 **전부 힙 메모리에 적재**하고 있었음. 방문자 × 채널 × 원문이 누적되며 힙이 고갈됨. (초기에는 힙 상한을 거는 미봉책으로 대응)
- **해결:** 순위 계산에는 채팅 원문이 필요 없고 **사용자별 카운트(집계값)만** 필요하다는 점을 파악. 원문을 힙에 쌓던 로직(`MemoryChatDataService`)을 제거하고, **Redis Sorted Set**으로 전환. 채팅 유입 시 `ZINCRBY`로 유저별 카운트만 증분하고, 정렬은 자료구조가 유지하므로 매 조회마다 전체를 다시 세고 정렬하던 연산이 사라짐.
- **결과:** 힙에 원문이 쌓이지 않아 OOM의 근본 원인 제거. 순위 조회는 정렬 유지 구조에서 즉시 반환.

### 2. 브라우저별 세션 식별 실패 → 세션 의존 제거

- **문제:** 모바일 일부 환경에서만, 수집 시작 후 잠시 뒤 화면에서 "수집 중 채널"이 사라짐. (백엔드 수집기는 정상 동작)
- **원인:** 프론트와 백엔드의 배포 도메인이 달라 쿠키가 서드파티로 취급됨. 브라우저별 쿠키 정책 차이로, 특정 브라우저(예: 삼성인터넷)에서는 요청마다 세션 식별자가 바뀌어 클라이언트가 자신의 수집 세션을 찾지 못함. (브라우저를 같은 조건으로 두고 비교해 디바이스가 아닌 브라우저가 원인임을 확정)
- **해결:** 브라우저별 예외 처리는 유지보수가 불가능하다고 판단, **서버 세션 의존 자체를 제거**. 클라이언트가 식별자를 직접 생성해 `localStorage`에 저장하는 방식으로 전환.
- **결과:** 브라우저 쿠키 정책에 종속되지 않는 안정적인 클라이언트 식별 확보.

### 3. 수집기 프로세스 증가로 인한 2차 OOM → 데몬 단일화

- **문제:** Redis 전환 후에도 다중 클라이언트 접속을 흉내 낸 부하 테스트에서 OOM 재발.
- **원인:** Docker에서 측정 시 **수집기 프로세스 하나당 약 40MB** 사용. 접속이 늘수록 프로세스가 선형으로 증가하는 구조.
- **해결:** 채널당 수집기 데몬을 하나만 두고 시청자를 구독자로 등록하는 구조로 전환(채널당 웹소켓 1개). 동시 구독 시 두 요청이 모두 "첫 구독자"로 판정돼 수집기가 중복 생성되는 경쟁 조건은 `ConcurrentHashMap.compute()`로 등록·판정을 원자적으로 처리해 방지.
- **결과:** 접속 수와 무관하게 채널당 자원이 일정하게 유지됨.

## 🚀 빠른 시작

### Docker로 실행 (권장)

```bash
# 저장소 클론
git clone https://github.com/jhm991231/TongNaMuKing.git
cd TongNaMuKing

# Docker Compose로 전체 시스템 실행
docker-compose up --build

# 접속
# - 웹 애플리케이션: http://localhost:5173
# - API 문서: http://localhost:8080/swagger-ui.html
```

코드 수정 후에는 `docker-compose up --build`로 다시 빌드하면 반영됩니다.

## 🛠️ 기술 스택

### Frontend
- **React 18** - 사용자 인터페이스
- **Vite** - 빌드 도구
- **CSS3** - 스타일링

### Backend
- **Spring Boot 3** - 웹 프레임워크
- **Spring Data JPA** - 데이터 접근
- **Spring Data Redis** - 실시간 순위 저장소 접근
- **MySQL** - 데이터베이스 (채팅 원본)
- **Redis 7** - 실시간 순위 (Sorted Set)
- **Swagger/OpenAPI** - API 문서화

### Chat Collector
- **Node.js** - 런타임
- **chzzk** - 치지직 API 라이브러리
- **axios** - HTTP 클라이언트

### Infrastructure
- **Docker & Docker Compose** - 컨테이너화
- **Railway** - 클라우드 배포
- **Upstash** - 운영 환경 Redis (서버리스)
- **Vercel** - 프론트엔드 호스팅

## 📁 프로젝트 구조

```
TongNaMuKing/
├── tongnamuking-frontend/      # React 프론트엔드
│   ├── src/
│   │   ├── App.jsx            # 멀티채널 앱
│   │   └── DogCakeApp.jsx     # 독케익 전용 앱
│   └── Dockerfile
├── tongnamuking-backend/       # Spring Boot 백엔드
│   ├── src/main/java/
│   │   ├── controller/        # REST API 컨트롤러
│   │   ├── service/           # 비즈니스 로직
│   │   ├── entity/            # JPA 엔티티
│   │   └── repository/        # 데이터 접근
│   ├── src/main/resources/
│   │   ├── application.properties          # 기본 설정
│   │   └── application-local.properties    # 로컬 개발 설정
│   └── Dockerfile
├── chat-collector/             # Node.js 채팅 수집기
│   ├── index.js              # 메인 수집 로직
│   └── package.json
├── docker-compose.yml         # 로컬 개발용
├── docker-compose.prod.yml    # 운영 배포용
├── Dockerfile                 # Railway 배포용
├── railway.json              # Railway 설정
└── README.md
```

## 🔧 환경 설정

로컬 실행 시 필요한 설정(MySQL, Redis, CORS)은 `docker-compose.yml`에 이미 포함되어 있어 별도 설정 없이 동작합니다.

### 배포 환경

#### Railway 환경변수
- `DATABASE_URL` - MySQL 연결 URL
- `DATABASE_USERNAME` - 데이터베이스 사용자명
- `DATABASE_PASSWORD` - 데이터베이스 비밀번호
- `SPRING_DATA_REDIS_URL` - Redis 연결 URL (Upstash `rediss://...`, TLS 자동 적용)
- `CORS_ALLOWED_ORIGINS` - 허용된 CORS 오리진

## 📊 주요 API

### 독케익 전용 API
- `POST /api/dogcake-collection/start` - 독케익 수집 시작
- `POST /api/dogcake-collection/stop` - 독케익 수집 중지
- `GET /api/dogcake-collection/status` - 독케익 수집 상태

### 멀티채널 API
- `POST /api/multi-channel-collection/start/{channelId}` - 채널 수집 시작
- `POST /api/multi-channel-collection/stop/{channelId}` - 채널 수집 중지
- `GET /api/multi-channel-collection/status` - 전체 수집 상태

### 통계 API
- `GET /api/chat-stats/channel/{channelName}` - 채널별 통계 (MySQL, 독케익용)
- `GET /api/chat-stats/client/channel/{channelName}?hours={N}` - 클라이언트별 실시간 순위 (Redis, hours 생략 시 전체 기간)

### 채널 검색 API
- `GET /api/channels/search?query={keyword}` - 채널 검색

## ⚡ 실시간 순위 설계 (Redis)

### 도입 배경

초기에는 멀티채널 채팅을 서버 힙 메모리(`ConcurrentHashMap`)에 저장하고, 순위 조회 시마다 전체 리스트를 순회·정렬했습니다. 이 구조에는 세 가지 문제가 있었습니다.

1. **조회 비용**: 10초마다 폴링될 때마다 최대 1만 건을 매번 재집계
2. **데이터 유실**: 서버 재시작·재배포 시 순위 전체 소멸
3. **카운트 왜곡**: 힙 보호를 위한 메시지 수 제한(채널당 1만 개) 초과 시 오래된 채팅이 삭제되며 순위 부정확

### 해결: Redis Sorted Set

순위에 필요한 것은 메시지 원문이 아니라 `유저 → 채팅 수` 카운트라는 점에 착안해, 저장 구조를 Sorted Set으로 교체했습니다.

```
쓰기: 채팅 1건 수신 → ZINCRBY (전체 순위 키 +1, 현재 분(minute) 버킷 키 +1)
읽기(전체 기간): ZREVRANGE — 자료구조가 정렬을 항상 유지하므로 재계산 없음, O(log N)
읽기(최근 N분): 1분 버킷들을 ZUNIONSTORE로 합산 → 결과를 10초 TTL 캐시로 재사용
```

### 키 설계

| 키 | 자료구조 | TTL | 용도 |
|---|---|---|---|
| `chat:rank:{clientId}:{channel}` | Sorted Set | 48h (갱신형) | 전체 기간 순위 |
| `chat:rank:{clientId}:{channel}:b:{yyyyMMddHHmm}` | Sorted Set | 2h | 1분 버킷 (시간범위 조회용) |
| `chat:rank:{clientId}:{channel}:union:{minutes}` | Sorted Set | 10s | 버킷 합산 결과 캐시 |

### 개선 효과

- 순위 조회: 전체 재집계 → 사전 정렬된 결과 즉시 반환
- 서버 재시작·재배포에도 순위 유지 (수집 세션과 데이터 수명 분리)
- 메시지 원문을 저장하지 않아 메모리 사용량이 고유 채터 수에만 비례 — 힙 메시지 제한/정리 스케줄러 제거
- 카운트만 유지하므로 순위 정확도 보장, TTL로 수명 관리 자동화

## 🎯 사용법

### 독케익 전용 모드
1. 메인 페이지 접속
2. 자동으로 독케익 채팅 수집 시작
3. 실시간 채팅 순위 확인
4. 저챗견 비율 분석 기능 사용

### 다른 채널 모드
1. 상단 토글로 "다른 채널 검색하기" 선택
2. 채널명으로 검색
3. 원하는 채널 선택 후 수집 시작 (최대 3개 채널)
4. 실시간 통계 확인

## 🐛 트러블슈팅

### 일반적인 문제

**1. Docker 빌드 실패**
```bash
# 모든 컨테이너 및 이미지 정리
docker-compose down
docker system prune -a
docker-compose up --build
```

**2. 채팅 수집이 안 될 때**
- 채널이 실제로 라이브 중인지 확인
- Docker 로그 확인: `docker logs tongnamuking-backend`
- Node.js 프로세스 상태 확인

**3. CORS 에러**
- 환경변수 `CORS_ALLOWED_ORIGINS` 확인
- 프론트엔드 URL이 올바른지 확인

**4. 데이터베이스 연결 실패**
- MySQL 컨테이너 상태 확인
- 로컬 개발 시 `--spring.profiles.active=local` 옵션 사용

**5. 순위가 표시되지 않을 때**
- Redis 컨테이너 상태 확인: `docker exec -it tongnamuking-redis redis-cli ping` (PONG이 나와야 정상)
- 운영 환경이면 `SPRING_DATA_REDIS_URL` 환경변수 설정 확인
- 데이터 확인: `redis-cli`에서 `KEYS chat:rank:*`

### 로그 확인
```bash
# 전체 로그
docker-compose logs

# 특정 서비스 로그
docker-compose logs backend
docker-compose logs frontend
docker-compose logs mysql
```

## 🌐 배포

### Railway 배포
1. Railway 프로젝트 생성
2. GitHub 저장소 연결
3. MySQL 서비스 추가
4. Upstash에서 Redis 생성 후 연결 URL 확보 (Railway 무료 플랜의 볼륨 제한으로 외부 Redis 사용)
5. 환경변수 설정 (`SPRING_DATA_REDIS_URL` 포함)
6. 자동 배포

### Vercel 프론트엔드 배포
1. Vercel 프로젝트 생성
2. GitHub 저장소 연결
3. Build Command: `npm run build`
4. Output Directory: `dist`
5. 환경변수 설정 (`VITE_API_BASE_URL`)

## 🤝 기여하기

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## 📝 라이센스

이 프로젝트는 MIT 라이센스 하에 배포됩니다. 자세한 내용은 [LICENSE](LICENSE) 파일을 참조하세요.

## 📞 문의

- 개발자: 정현민
- 이메일: jhm991231@gmail.com
- 프로젝트 링크: [https://github.com/jhm991231/TongNaMuKing](https://github.com/jhm991231/TongNaMuKing)

---

**⚠️ 주의사항**
- 대량의 채팅 수집 시 서버 리소스를 고려해주세요
- 치지직 서비스 이용약관을 준수해주세요
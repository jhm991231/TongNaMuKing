# 🪵 TongNaMuKing (채팅 통나무)

치지직 실시간 채팅 수집 및 통계 분석 시스템

## 📋 프로젝트 개요

TongNaMuKing은 치지직(Chzzk) 스트리밍 플랫폼의 실시간 채팅을 수집하고 분석하는 웹 애플리케이션입니다.

### 주요 기능

- 🔴 **실시간 채팅 수집**: 치지직 채널의 실시간 채팅 메시지 수집
- 📊 **채팅 통계**: 사용자별 채팅 횟수 순위 및 통계 분석
- 🐕 **독케익 전용 기능**: 독케익 채널 전용 수집 및 저챗견 비율 분석
- 🔀 **멀티채널 지원**: 최대 3개 채널 동시 수집 (메모리 기반)
- 🔍 **채널 검색**: 치지직 플랫폼의 모든 채널 검색 및 수집

## 🏗️ 시스템 아키텍처

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   React         │    │   Spring Boot    │    │     MySQL       │
│   Frontend      │◄──►│   Backend        │◄──►│   Database      │
│   (Port 5173)   │    │   (Port 8080)    │    │   (Port 3306)   │
└─────────────────┘    └──────────────────┘    └─────────────────┘
                                │
                                ▼
                       ┌──────────────────┐
                       │   Node.js        │
                       │   Chat Collector │
                       │   (치지직 API)    │
                       └──────────────────┘
```

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

### 로컬 개발 환경

#### 사전 요구사항
- Java 21
- Node.js 18+
- MySQL 8.0+

#### 1. 데이터베이스 설정
```sql
CREATE DATABASE tongnamuking;
```

#### 2. 백엔드 실행
```bash
cd tongnamuking-backend

# 로컬 개발 환경으로 실행 (권장)
./gradlew bootRun --args='--spring.profiles.active=local'
```

#### 3. 프론트엔드 실행
```bash
cd tongnamuking-frontend
npm install
npm run dev
```

#### 4. Chat Collector 의존성 설치
```bash
cd chat-collector
npm install
```

## 🛠️ 기술 스택

### Frontend
- **React 18** - 사용자 인터페이스
- **Vite** - 빌드 도구
- **CSS3** - 스타일링

### Backend
- **Spring Boot 3** - 웹 프레임워크
- **Spring Data JPA** - 데이터 접근
- **MySQL** - 데이터베이스
- **Swagger/OpenAPI** - API 문서화

### Chat Collector
- **Node.js** - 런타임
- **chzzk** - 치지직 API 라이브러리
- **axios** - HTTP 클라이언트

### Infrastructure
- **Docker & Docker Compose** - 컨테이너화
- **Railway** - 클라우드 배포
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

### 로컬 개발 환경

#### Frontend (.env)
```env
VITE_API_BASE_URL=http://localhost:8080
```

#### Backend (application-local.properties)
```properties
# MySQL Database Configuration
spring.datasource.url=jdbc:mysql://localhost:3306/tongnamuking?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
spring.datasource.username=root
spring.datasource.password=1234

# CORS 설정
cors.allowed.origins=http://localhost:5173,http://localhost:3000

# JPA Configuration
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
```

### 배포 환경

#### Railway 환경변수
- `DATABASE_URL` - MySQL 연결 URL
- `DATABASE_USERNAME` - 데이터베이스 사용자명
- `DATABASE_PASSWORD` - 데이터베이스 비밀번호
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
- `GET /api/chat-stats/channel/{channelName}` - 채널별 통계 (DB)
- `GET /api/chat-stats/session/channel/{channelName}` - 세션별 통계 (메모리)

### 채널 검색 API
- `GET /api/channels/search?query={keyword}` - 채널 검색

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
4. 환경변수 설정
5. 자동 배포

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
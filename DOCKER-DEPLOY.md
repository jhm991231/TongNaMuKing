# 🐳 TongNaMuKing Docker 배포 가이드

## 📋 사전 요구사항

- Docker 20.0+
- Docker Compose 2.0+
- Git

## 🚀 빠른 시작

### 1. 프로젝트 클론
```bash
git clone <repository-url>
cd TongNaMuKing
```

### 2. 배포 스크립트 실행
```bash
chmod +x docker-deploy.sh
./docker-deploy.sh
```

### 3. 수동 배포 (선택사항)
```bash
# 개발 환경
docker-compose up --build -d

# 프로덕션 환경
docker-compose -f docker-compose.prod.yml up --build -d
```

## 🌐 서비스 접속

- **Frontend**: http://localhost
- **Backend API**: http://localhost:8080
- **MySQL**: localhost:3306

## 🔧 환경 설정

### 프로덕션 환경 변수
프로덕션 배포 시 `.env` 파일을 생성하세요:

```env
DB_PASSWORD=your_secure_password
```

### 개발 환경
기본 설정으로 바로 실행 가능합니다.

## 📊 모니터링

### 컨테이너 상태 확인
```bash
docker-compose ps
```

### 로그 확인
```bash
# 전체 서비스 로그
docker-compose logs -f

# 특정 서비스 로그
docker-compose logs -f backend
docker-compose logs -f frontend
docker-compose logs -f mysql
```

### 컨테이너 내부 접속
```bash
# Backend 컨테이너 접속
docker exec -it tongnamuking-backend bash

# MySQL 컨테이너 접속
docker exec -it tongnamuking-mysql mysql -u root -p
```

## 🛑 서비스 중지

```bash
# 컨테이너 중지
docker-compose down

# 볼륨까지 삭제 (데이터베이스 데이터 삭제됨!)
docker-compose down -v
```

## 🔄 업데이트

```bash
# 코드 업데이트 후 재배포
git pull
docker-compose down
docker-compose up --build -d
```

## 🐛 문제 해결

### 포트 충돌
기본 포트가 사용 중인 경우 docker-compose.yml에서 포트를 변경하세요:

```yaml
services:
  frontend:
    ports:
      - "8081:80"  # 80 → 8081로 변경
  backend:
    ports:
      - "8082:8080"  # 8080 → 8082로 변경
```

### 데이터베이스 초기화
```bash
docker-compose down -v
docker volume rm tongnamuking_mysql_data
docker-compose up --build -d
```

### 컨테이너 재빌드
```bash
docker-compose build --no-cache
docker-compose up -d
```

## 📁 디렉토리 구조

```
TongNaMuKing/
├── docker-compose.yml          # 개발 환경 설정
├── docker-compose.prod.yml     # 프로덕션 환경 설정
├── docker-deploy.sh           # 배포 스크립트
├── tongnamuking-frontend/
│   ├── Dockerfile
│   ├── nginx.conf
│   └── .dockerignore
├── tongnamuking-backend/
│   ├── Dockerfile
│   └── .dockerignore
└── chat-collector/
    ├── Dockerfile
    └── .dockerignore
```

## 🔒 보안 고려사항

1. **프로덕션에서는 강력한 데이터베이스 비밀번호 사용**
2. **MySQL 포트를 외부에 노출하지 않기** (docker-compose.prod.yml에서 ports 제거)
3. **HTTPS 사용** (Reverse Proxy 설정)
4. **정기적인 백업 수행**

## 📈 성능 최적화

1. **프로덕션에서는 SQL 로그 비활성화**
2. **MySQL 설정 튜닝**
3. **Nginx 캐싱 설정**
4. **리소스 제한 설정**
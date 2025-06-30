# 치지직 채팅 수집기

kimcore/chzzk 라이브러리를 사용해서 실시간 채팅을 수집하고 Java 백엔드로 전송하는 Node.js 스크립트입니다.

## 설치

```bash
cd chat-collector
npm install
```

## 사용법

### 1. Java 백엔드 실행
먼저 Java 백엔드가 실행되어 있어야 합니다:
```bash
cd ../tongnamuking-backend
./gradlew bootRun
```

### 2. 채팅 수집 시작
```bash
# 기본 실행
node index.js <channelId>

# 예시
node index.js a7e175625fdea5a7d98428302b7aa57f

# 개발 모드 (파일 변경 시 자동 재시작)
npm run dev <channelId>
```

### 3. 프로그램 종료
`Ctrl+C`로 종료

## 기능

- 실시간 채팅 메시지 수집
- 후원 메시지 수집
- 자동 재연결
- Java 백엔드로 데이터 전송
- 데이터베이스 자동 저장

## 수집되는 데이터

- 사용자명/닉네임
- 채팅 메시지
- 타임스탬프
- 후원 금액 (후원 메시지인 경우)

## 주의사항

- 채널이 방송 중이어야 채팅을 수집할 수 있습니다
- Java 백엔드(port 8080)가 실행 중이어야 합니다
- 인터넷 연결이 필요합니다
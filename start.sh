#!/bin/bash

# Start nginx
service nginx start

# Install chat-collector dependencies
cd /app/chat-collector && npm install

# Start backend
cd /app
# 2026-08-04 측정으로 근거를 잡은 값이다.
# docs/superpowers/notes/2026-08-01-memory-measurement-results.md 참고.
#   힙        실사용 35MB  ← 종전 상한 192m 은 5.5배 과잉, Xms 128m 은 커밋 낭비
#   메타스페이스 committed 107MB ← 종전 상한 128m 은 여유 21MB 로 빠듯했다
# 두 상한이 실제 사용과 거꾸로 배분돼 있어 힙에서 덜어 메타스페이스로 옮겼다.
java -Xmx128m -Xms64m \
  -XX:MaxMetaspaceSize=160m \
  -XX:MaxDirectMemorySize=32m \
  -Xss512k \
  -XX:+HeapDumpOnOutOfMemoryError -jar backend.jar &

# Wait for any process to exit
wait -n

# Exit with status of process that exited first
exit $?
#!/usr/bin/env bash
# scripts/measure/load-chat.sh <채널ID> <건수> [동시성]
#
# 수집기 수신 엔드포인트에 채팅을 직접 밀어넣는다. 데몬도 치지직도 필요 없다.
# (MultiChannelControllerFanOutIsolationTest 가 쓰는 것과 같은 경로)
#
# 채팅 한 건은 그 채널의 구독자 수만큼 Redis 쓰기를 일으킨다(팬아웃).
set -euo pipefail

CHANNEL="${1:?사용법: load-chat.sh <채널ID> <건수> [동시성]}"
TOTAL="${2:?사용법: load-chat.sh <채널ID> <건수> [동시성]}"
CONCURRENCY="${3:-10}"
BASE="${BASE_URL:-http://localhost:8080}"

# 사용자 이름을 몇 종류로 돌릴지. 키 개수가 아니라 "키 하나의 크기"를 좌우한다
# (Sorted Set 의 멤버 수 = 고유 사용자 수).
DISTINCT_USERS="${DISTINCT_USERS:-50}"

send_one() {
  local n="$1"
  local user=$(( n % DISTINCT_USERS + 1 ))
  curl -s -o /dev/null -X POST \
    -H "Content-Type: application/json" \
    -d "{\"channelId\":\"${CHANNEL}\",\"channelName\":\"${CHANNEL}\",\"username\":\"chatter-${user}\"}" \
    "${BASE}/api/multi-channel-collection/message/from-collector"
}
export -f send_one
export CHANNEL BASE DISTINCT_USERS

start=$(date +%s)
seq 1 "$TOTAL" | xargs -P "$CONCURRENCY" -I{} bash -c 'send_one {}'
elapsed=$(( $(date +%s) - start ))

echo "채팅 ${TOTAL}건 주입 완료 (동시성 ${CONCURRENCY}, 고유 사용자 ${DISTINCT_USERS}명, ${elapsed}초)"

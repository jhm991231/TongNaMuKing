#!/usr/bin/env bash
# scripts/measure/load-clients.sh <채널ID> <N>
#
# 서로 다른 X-Client-ID 로 N 명이 같은 채널을 구독한 상태를 만든다.
# ClientIdentifierService 가 X-Client-ID 헤더를 클라이언트 식별자로 쓰므로
# (ClientIdentifierService:23) 헤더만 바꾸면 별개 클라이언트로 취급된다.
set -euo pipefail

CHANNEL="${1:?사용법: load-clients.sh <채널ID> <N>}"
COUNT="${2:?사용법: load-clients.sh <채널ID> <N>}"
BASE="${BASE_URL:-http://localhost:8080}"

ok=0
fail=0
for i in $(seq 1 "$COUNT"); do
  # 응답 본문에 수집 시작 성공 여부가 담긴다(HTTP 는 실패해도 200 이다).
  body=$(curl -s -X POST \
    -H "X-Client-ID: measure-client-${i}" \
    "${BASE}/api/multi-channel-collection/start/${CHANNEL}")

  # 이미 구독한 클라이언트가 다시 요청하면 success:false 에 "이미 ... 수집 중" 이 온다.
  # 레지스트리에는 남아 있으므로 측정 관점에서는 성공으로 센다.
  case "$body" in
    *'"success":true'*)   ok=$((ok + 1)) ;;
    *'이미 해당 채널'*)    ok=$((ok + 1)) ;;
    *)                    fail=$((fail + 1)) ;;
  esac
done

echo "구독 요청 ${COUNT}건 — 성공 ${ok}, 실패 ${fail} (채널 ${CHANNEL})"
if [ "$fail" -gt 0 ]; then
  echo "  마지막 응답: ${body}"
  echo "  실패가 있으면 레지스트리에 클라이언트가 남지 않아 C축 측정이 성립하지 않는다."
fi

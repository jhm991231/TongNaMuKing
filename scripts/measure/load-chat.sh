#!/usr/bin/env bash
# scripts/measure/load-chat.sh <채널ID> <건수> [유지할_클라이언트_수]
#
# 수집기 수신 엔드포인트에 채팅을 직접 밀어넣는다. 데몬도 치지직도 필요 없다.
# (MultiChannelControllerFanOutIsolationTest 가 쓰는 것과 같은 경로)
#
# 채팅 한 건은 그 채널의 구독자 수만큼 Redis 쓰기를 일으킨다(팬아웃).
#
# ── 속도 ──────────────────────────────────────────────────────────────
# 요청마다 curl 프로세스를 띄우면 40건/초까지밖에 안 나온다(프로세스 생성 비용이
# 대부분이다). curl 설정 파일(-K)에 요청을 모아 한 프로세스로 보내면 TCP 연결도
# 재사용되어 훨씬 빠르다.
#
# ── 구독 유지 ─────────────────────────────────────────────────────────
# MultiChannelCollectionService.cleanupInactiveCilents(:203)가 30초마다 돌며
# 2분 이상 요청이 없는 클라이언트의 구독을 해제한다. 주입이 길어지면 측정 도중
# 구독자가 사라져 채팅이 아무 데도 안 쓰인다. 세 번째 인자로 클라이언트 수를 주면
# 청크마다 status 를 찔러 활동 시각을 갱신한다.
set -euo pipefail

CHANNEL="${1:?사용법: load-chat.sh <채널ID> <건수> [유지할_클라이언트_수]}"
TOTAL="${2:?사용법: load-chat.sh <채널ID> <건수> [유지할_클라이언트_수]}"
KEEPALIVE_CLIENTS="${3:-0}"
BASE="${BASE_URL:-http://localhost:8080}"

# 사용자 이름을 몇 종류로 돌릴지. 키 개수가 아니라 "키 하나의 크기"를 좌우한다
# (Sorted Set 의 멤버 수 = 고유 사용자 수).
DISTINCT_USERS="${DISTINCT_USERS:-50}"

# 한 번의 curl 로 보낼 요청 수. 청크 사이에 구독 갱신이 들어간다.
CHUNK="${CHUNK:-2000}"

ENDPOINT="${BASE}/api/multi-channel-collection/message/from-collector"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# 페이로드는 고유 사용자 수만큼만 필요하므로 미리 파일로 만들어 두고
# 설정 파일에서 data = "@파일" 로 참조한다. 따옴표 이스케이프 문제도 사라진다.
for u in $(seq 1 "$DISTINCT_USERS"); do
  printf '{"channelId":"%s","channelName":"%s","username":"chatter-%d"}' \
    "$CHANNEL" "$CHANNEL" "$u" > "$WORK/p${u}.json"
done

keepalive() {
  [ "$KEEPALIVE_CLIENTS" -gt 0 ] || return 0
  local i
  for i in $(seq 1 "$KEEPALIVE_CLIENTS"); do
    curl -s -o /dev/null -H "X-Client-ID: measure-client-${i}" \
      "${BASE}/api/multi-channel-collection/status" || true
  done
}

send_chunk() {
  local from="$1" to="$2" cfg n user first=1
  cfg="$WORK/req.conf"
  : > "$cfg"
  for ((n = from; n <= to; n++)); do
    user=$(( n % DISTINCT_USERS + 1 ))
    [ "$first" -eq 1 ] || echo "next" >> "$cfg"
    first=0
    # 경로를 상대로 쓴다. Git Bash 의 /tmp/... 는 POSIX 경로라 윈도우 curl 이
    # 읽지 못한다(설정 파일 "안"의 경로는 MSYS 가 변환해 주지 않는다).
    # 아래에서 작업 디렉터리를 옮겨 실행하므로 파일명만으로 충분하다.
    {
      echo "url = \"${ENDPOINT}\""
      echo 'request = "POST"'
      echo 'header = "Content-Type: application/json"'
      echo "data = \"@p${user}.json\""
      echo 'silent'
    } >> "$cfg"
  done
  # 응답 본문은 통째로 버린다(설정 파일의 output 은 윈도우에서 /dev/null 을 못 쓴다).
  ( cd "$WORK" && curl -K req.conf ) > /dev/null
}

start=$(date +%s)
sent=0
while [ "$sent" -lt "$TOTAL" ]; do
  remain=$(( TOTAL - sent ))
  size=$(( remain < CHUNK ? remain : CHUNK ))
  send_chunk $(( sent + 1 )) $(( sent + size ))
  sent=$(( sent + size ))
  keepalive
done
elapsed=$(( $(date +%s) - start ))

rate="?"
[ "$elapsed" -gt 0 ] && rate=$(( TOTAL / elapsed ))
echo "채팅 ${TOTAL}건 주입 완료 (고유 사용자 ${DISTINCT_USERS}명, ${elapsed}초, 약 ${rate}건/초)"
[ "$KEEPALIVE_CLIENTS" -gt 0 ] && echo "  구독 유지: 클라이언트 ${KEEPALIVE_CLIENTS}명, 청크(${CHUNK}건)마다 갱신"

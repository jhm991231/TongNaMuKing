#!/usr/bin/env bash
# scripts/measure/load-buckets.sh <클라이언트ID> <채널명> <분수> [멤버수]
#
# 과거 N 분에 해당하는 버킷 키를 직접 만들어, 오래 방송한 상태를 즉시 재현한다.
#
# ChatRankingService 는 시간범위 순위용으로 1분 단위 버킷 키를 만들고(BUCKET_FORMAT
# "yyyyMMddHHmm") 2시간 보관한다(BUCKET_TTL). 따라서 채팅이 매분 들어오는 (클라이언트,
# 채널) 쌍 하나는 정상 상태에서 버킷 키를 최대 120개 유지한다.
#
# 애플리케이션 경로로 재현하려면 두 시간 이상 부하를 유지해야 하는데, 측정 목적은
# "버킷 120개가 차지하는 메모리"를 아는 것이지 그것이 어떻게 쌓였는지가 아니므로
# 결과가 같다. 다만 이 축의 수치는 합성된 것임을 결과 문서에 명시할 것.
#
# 키 이름 규칙은 ChatRankingService.bucketKey 와 반드시 같아야 한다:
#   chat:rank:{clientId}:{channelName}:b:{yyyyMMddHHmm}
set -euo pipefail

CLIENT="${1:?사용법: load-buckets.sh <클라이언트ID> <채널명> <분수> [멤버수]}"
CHANNEL="${2:?사용법: load-buckets.sh <클라이언트ID> <채널명> <분수> [멤버수]}"
MINUTES="${3:?사용법: load-buckets.sh <클라이언트ID> <채널명> <분수> [멤버수]}"
MEMBERS="${4:-50}"
REDIS=tongnamuking-redis

BASE_KEY="chat:rank:${CLIENT}:${CHANNEL}"
CMDS=$(mktemp)
trap 'rm -f "$CMDS"' EXIT

# 컨테이너가 UTC 로 돌아간다(관측: 호스트 16:31 KST 일 때 버킷이 0731). 애플리케이션의
# LocalDateTime.now() 도 컨테이너 시각이므로 UTC 로 만든다. Step 2 에서 애플리케이션이
# 만든 실제 키와 대조해 확인할 것.
for ((m = 0; m < MINUTES; m++)); do
  stamp=$(date -u -d "-${m} minutes" +%Y%m%d%H%M)
  key="${BASE_KEY}:b:${stamp}"

  # ZADD 는 ZINCRBY 와 같은 자료구조(Sorted Set)를 만든다.
  printf 'ZADD %s' "$key" >> "$CMDS"
  for ((u = 1; u <= MEMBERS; u++)); do
    printf ' 1 chatter-%d' "$u" >> "$CMDS"
  done
  printf '\n' >> "$CMDS"

  echo "EXPIRE ${key} 7200" >> "$CMDS"
done

# 명령을 한 번에 흘려보낸다. 키마다 docker exec 를 부르면 키 하나당 0.5초씩 걸린다.
docker exec -i "$REDIS" redis-cli < "$CMDS" > /dev/null

echo "버킷 키 ${MINUTES}개 생성 (${BASE_KEY}:b:*, 키당 멤버 ${MEMBERS}명)"

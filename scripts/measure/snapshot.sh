#!/usr/bin/env bash
# scripts/measure/snapshot.sh <라벨>
#
# 각 계층의 메모리를 한 줄 CSV로 출력하고, 원시 출력은 measurements/<라벨>/ 에 남긴다.
# 파싱 결과만 남기면 나중에 재해석할 수 없으므로 원본을 항상 보존한다.
set -euo pipefail

LABEL="${1:?사용법: snapshot.sh <라벨>}"
BACKEND=tongnamuking-backend
REDIS=tongnamuking-redis
OUT="measurements/${LABEL}"
mkdir -p "$OUT"

# ── 강제 GC ─────────────────────────────────────────────────────────────
# 이걸 빼면 "살아있는 객체가 얼마인가"가 아니라 "GC가 마침 언제 돌았는가"를 재게 된다.
# 축을 늘려가며 비교하는 측정에서 이 노이즈는 결론을 뒤집을 수 있다.
docker exec "$BACKEND" jcmd 1 GC.run > /dev/null
sleep 2

# ── 원시 출력 보존 ──────────────────────────────────────────────────────
# redis-cli 는 CRLF 로 응답하므로 저장할 때 CR 을 떼어낸다.
docker exec "$BACKEND" jcmd 1 GC.heap_info             > "$OUT/heap_info.txt"
docker exec "$BACKEND" jcmd 1 VM.native_memory summary > "$OUT/nmt.txt"
docker exec "$REDIS" redis-cli INFO memory | tr -d '\r' > "$OUT/redis_info.txt"
docker exec "$REDIS" redis-cli INFO stats  | tr -d '\r' > "$OUT/redis_stats.txt"
docker exec "$REDIS" redis-cli DBSIZE      | tr -d '\r' > "$OUT/redis_dbsize.txt"
docker exec "$BACKEND" curl -s http://127.0.0.1:3001/health > "$OUT/daemon_health.json" \
  || echo '{"ok":false,"note":"데몬 미기동"}' > "$OUT/daemon_health.json"
docker stats --no-stream --format '{{.Name}},{{.MemUsage}},{{.MemPerc}}' > "$OUT/docker_stats.txt"

# ── 파싱 ────────────────────────────────────────────────────────────────
# 파이프라인 대신 awk 한 번으로 뽑는다. 값이 없어도 0 을 내므로 set -e 에 걸리지 않는다.
#
# 힙은 "세대" 줄만 더한다. Task 1 에서 확인한 실제 출력(SerialGC):
#
#   def new generation   total 39424K, used 18068K [...      ← 힙
#   tenured generation   total 87424K, used 37678K [...      ← 힙
#   Metaspace       used 100289K, committed 101184K, ...     ← 힙 아님(별도 네이티브 영역)
#     class space   used  13364K, ...                        ← Metaspace 의 일부
#
# 'used [0-9]+K' 를 전부 더하면 Metaspace 와 class space 까지 섞여 세 배 넘게 부푼다.
# eden/from/to/the space 줄은 'NN% used' 형식이라 이 패턴에 걸리지 않는다.
#
# ⚠️ 이 식은 SerialGC 출력 기준이다. 예산이나 CPU 를 늘려 JVM 이 G1 을 고르면
#    'garbage-first heap total ..., used ...' 한 줄 형식이 되어 0 이 찍힌다.
#    측정 조건을 바꾼 뒤에는 원시 파일과 반드시 대조할 것.
heap_kb=$(awk '
  /(new|tenured) generation/ {
    if (match($0, /used [0-9]+K/)) sum += substr($0, RSTART + 5, RLENGTH - 6)
  }
  END { print sum + 0 }
' "$OUT/heap_info.txt")

# Metaspace 는 힙과 별개다. heap_info 에 이미 나오므로 NMT 없이도 얻을 수 있다.
meta_kb=$(awk '
  /^[[:space:]]*Metaspace/ {
    if (match($0, /used [0-9]+K/)) { print substr($0, RSTART + 5, RLENGTH - 6); exit }
  }
' "$OUT/heap_info.txt")

redis_b=$(awk -F: '/^used_memory:/ { print $2; exit }' "$OUT/redis_info.txt")

# 축출이 시작되면 메모리가 더 안 늘어난다 — 한도에 닿았다는 신호이지 여유가 아니다.
evicted=$(awk -F: '/^evicted_keys:/ { print $2; exit }' "$OUT/redis_stats.txt")

keys=$(awk '{ gsub(/[^0-9]/, ""); if ($0 != "") { print $0; exit } }' "$OUT/redis_dbsize.txt")

daemon_rss=$(awk 'match($0, /"rss":[0-9]+/) {
    print substr($0, RSTART + 6, RLENGTH - 6); exit }' "$OUT/daemon_health.json")
channels=$(awk 'match($0, /"channels":[0-9]+/) {
    print substr($0, RSTART + 11, RLENGTH - 11); exit }' "$OUT/daemon_health.json")

container=$(awk -F, -v name="$BACKEND" '$1 == name { print $2; exit }' "$OUT/docker_stats.txt")

echo "${LABEL},${heap_kb:-0},${meta_kb:-0},${redis_b:-0},${keys:-0},${evicted:-0},${daemon_rss:-0},${container:-?},${channels:-0}"

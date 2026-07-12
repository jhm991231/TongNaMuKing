package com.tongnamuking.tongnamuking_backend.service;

import com.tongnamuking.tongnamuking_backend.dto.ChatStatsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Redis Sorted Set 기반 실시간 채팅 순위 서비스.
 *
 * - 전체 순위: 채팅 수신 시 ZINCRBY로 증분 갱신, 조회는 이미 정렬된 결과를 꺼내기만 한다.
 *   (기존 방식: 조회할 때마다 전체 채팅 리스트를 순회+정렬)
 * - 시간범위 순위: 1분 단위 버킷 키에 나눠 카운트하고, 조회 시 해당 범위의
 *   버킷들을 ZUNIONSTORE로 합산한다. 합산 결과는 10초 캐시로 재사용한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatRankingService {

    private final StringRedisTemplate redisTemplate;

    /** 전체 순위 키: 마지막 채팅 수신 후 48시간 뒤 자동 삭제 (갱신형 TTL) */
    private static final Duration RANKING_TTL = Duration.ofHours(48);

    /** 버킷 키: 시간범위 조회 최대값(1시간)보다 여유 있게 2시간 보관 (고정형 TTL) */
    private static final Duration BUCKET_TTL = Duration.ofHours(2);

    /** 합산 결과 캐시: 프론트 폴링 주기(10초)와 동일 */
    private static final Duration UNION_CACHE_TTL = Duration.ofSeconds(10);

    private static final DateTimeFormatter BUCKET_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private String key(String clientId, String channelName) {
        return "chat:rank:" + clientId + ":" + channelName;
    }

    private String bucketKey(String clientId, String channelName, LocalDateTime time) {
        return key(clientId, channelName) + ":b:" + time.format(BUCKET_FORMAT);
    }

    /** 채팅 1건 수신 시 전체 순위 +1, 현재 분(minute) 버킷 +1 */
    public void incrementScore(String clientId, String channelName, String username) {
        String totalKey = key(clientId, channelName);
        redisTemplate.opsForZSet().incrementScore(totalKey, username, 1);
        redisTemplate.expire(totalKey, RANKING_TTL);

        String bucket = bucketKey(clientId, channelName, LocalDateTime.now());
        redisTemplate.opsForZSet().incrementScore(bucket, username, 1);
        redisTemplate.expire(bucket, BUCKET_TTL);
    }

    /** 전체 순위 조회 — Sorted Set이 항상 정렬 상태를 유지하므로 재계산 없음 */
    public List<ChatStatsResponse> getRanking(String clientId, String channelName) {
        return readRanking(key(clientId, channelName));
    }

    /**
     * 시간범위 순위 조회 — 최근 N분의 버킷들을 ZUNIONSTORE로 합산.
     * 합산 결과는 10초 TTL 캐시로 저장해 연속 조회 시 재합산을 막는다.
     */
    public List<ChatStatsResponse> getRankingByTimeRange(String clientId, String channelName, double hours) {
        long minutes = Math.max(1, Math.round(hours * 60));
        String unionKey = key(clientId, channelName) + ":union:" + minutes;

        Long ttl = redisTemplate.getExpire(unionKey);
        if (ttl == null || ttl <= 0) {
            LocalDateTime now = LocalDateTime.now();
            List<String> buckets = new ArrayList<>();
            for (long i = 0; i < minutes; i++) {
                buckets.add(bucketKey(clientId, channelName, now.minusMinutes(i)));
            }
            redisTemplate.opsForZSet()
                    .unionAndStore(buckets.get(0), buckets.subList(1, buckets.size()), unionKey);
            redisTemplate.expire(unionKey, UNION_CACHE_TTL);
        }
        return readRanking(unionKey);
    }

    /** 키의 Sorted Set을 점수 내림차순으로 읽어 순위 응답으로 변환 */
    private List<ChatStatsResponse> readRanking(String key) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeWithScores(key, 0, -1);

        List<ChatStatsResponse> result = new ArrayList<>();
        if (tuples == null) {
            return result;
        }

        int rank = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            long count = tuple.getScore() != null ? tuple.getScore().longValue() : 0L;
            result.add(new ChatStatsResponse((long) rank, tuple.getValue(), count, rank));
            rank++;
        }
        return result;
    }
}

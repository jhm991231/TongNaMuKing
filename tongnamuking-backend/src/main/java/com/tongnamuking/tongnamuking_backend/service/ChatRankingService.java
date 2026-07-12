package com.tongnamuking.tongnamuking_backend.service;

import com.tongnamuking.tongnamuking_backend.dto.ChatStatsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Redis Sorted Set 기반 실시간 채팅 순위 서비스.
 * 채팅 수신 시 ZINCRBY로 증분 갱신하고, 조회는 이미 정렬된 결과를 꺼내기만 한다.
 * (기존 방식: 조회할 때마다 전체 채팅 리스트를 순회+정렬)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatRankingService {

    private final StringRedisTemplate redisTemplate;

    /** 마지막 채팅 수신 후 48시간 뒤 자동 삭제 (갱신형 TTL) */
    private static final Duration RANKING_TTL = Duration.ofHours(48);

    private String key(String clientId, String channelName) {
        return "chat:rank:" + clientId + ":" + channelName;
    }

    /** 채팅 1건 수신 시 해당 유저 점수 +1 */
    public void incrementScore(String clientId, String channelName, String username) {
        String key = key(clientId, channelName);
        redisTemplate.opsForZSet().incrementScore(key, username, 1);
        redisTemplate.expire(key, RANKING_TTL);
    }

    /** 전체 순위 조회 — Sorted Set이 항상 정렬 상태를 유지하므로 재계산 없음 */
    public List<ChatStatsResponse> getRanking(String clientId, String channelName) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeWithScores(key(clientId, channelName), 0, -1);

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

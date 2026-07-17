package com.tongnamuking.tongnamuking_backend.controller;

import com.tongnamuking.tongnamuking_backend.dto.ChatMessageRequest;
import com.tongnamuking.tongnamuking_backend.service.ChannelSubscriptionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * finding 2: addMultiChannelMessage의 팬아웃 격리(구독자별 try/catch, ~145-155행)를
 * 실제 Redis로 검증한다.
 *
 * 목킹 프레임워크나 새 의존성을 추가하지 않는다. 이 프로젝트에 이미 있는
 * {@code @SpringBootTest} 선례({@link com.tongnamuking.tongnamuking_backend.TongnamukingBackendApplicationTests})와,
 * docker-compose로 띄운 실제 Redis(로컬 6379)를 그대로 사용한다.
 *
 * 한 구독자(brokenClient)의 랭킹 키를 미리 Redis String 타입으로 세팅해 두면, 그 클라이언트에
 * 대해서만 {@code ChatRankingService.incrementScore}의 ZINCRBY가 진짜 WRONGTYPE 에러를 던진다.
 * 팬아웃 루프에 구독자별 try/catch 격리가 없다면 이 예외가 루프 전체를 중단시켜, 반복 순서상
 * 그 뒤에 있던 나머지 구독자들은 이번 채팅을 영구히 놓치게 된다. 격리가 있다면(현재 구현)
 * 다른 구독자들의 점수는 정상적으로 반영되고 엔드포인트는 여전히 성공을 응답해야 한다.
 *
 * 구독은 {@code ChannelSubscriptionRegistry}에 직접 추가한다 — collector.daemon.enabled=false라서
 * CollectorDaemonManager.subscribe()는 항상 false를 반환하므로, 실제 startCollection()을 거치면
 * 데몬 구독 실패로 롤백되어 구독자를 등록할 수 없다. 이 테스트는 팬아웃(레지스트리 조회 이후의
 * Redis 반영 로직) 격리만 검증하므로 레지스트리를 직접 시딩해도 검증 대상과 무관하다.
 */
@SpringBootTest(properties = "collector.daemon.enabled=false")
class MultiChannelControllerFanOutIsolationTest {

    @Autowired
    private MultiChannelController controller;

    @Autowired
    private ChannelSubscriptionRegistry registry;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final String channelName = "test-channel-" + UUID.randomUUID();
    private final String healthyClientA = "client-A-" + UUID.randomUUID();
    private final String brokenClientB = "client-B-" + UUID.randomUUID();
    private final String healthyClientC = "client-C-" + UUID.randomUUID();
    private final String earlySubscriber = "early-" + UUID.randomUUID();
    private final String lateSubscriber = "late-" + UUID.randomUUID();

    private String rankKey(String clientId) {
        return "chat:rank:" + clientId + ":" + channelName;
    }

    @AfterEach
    void cleanUp() {
        // 이 테스트가 registry와 Redis에 만든 상태를 남김없이 지운다.
        registry.remove(channelName, healthyClientA);
        registry.remove(channelName, brokenClientB);
        registry.remove(channelName, healthyClientC);
        registry.remove(channelName, earlySubscriber);
        registry.remove(channelName, lateSubscriber);

        for (String clientId : Set.of(healthyClientA, brokenClientB, healthyClientC, earlySubscriber, lateSubscriber)) {
            redisTemplate.delete(rankKey(clientId));
            Set<String> bucketKeys = redisTemplate.keys(rankKey(clientId) + ":b:*");
            if (bucketKeys != null && !bucketKeys.isEmpty()) {
                redisTemplate.delete(bucketKeys);
            }
        }
    }

    @Test
    void 한_구독자의_WRONGTYPE_에러가_다른_구독자의_집계를_막지_않는다() {
        // 채널에 세 클라이언트를 구독시킨다 (레지스트리 직접 시딩 — 데몬을 거치지 않는다).
        registry.add(channelName, healthyClientA);
        registry.add(channelName, brokenClientB);
        registry.add(channelName, healthyClientC);

        // brokenClientB의 랭킹 키를 잘못된 타입(String)으로 미리 세팅한다.
        // ChatRankingService.incrementScore의 ZINCRBY가 이 키에 대해 진짜 WRONGTYPE
        // 에러를 던지게 만든다.
        redisTemplate.opsForValue().set(rankKey(brokenClientB), "not-a-sorted-set");

        ChatMessageRequest request = new ChatMessageRequest();
        request.setChannelId(channelName);
        request.setChannelName(channelName);
        request.setUsername("chatter1");

        ResponseEntity<String> response = controller.addMultiChannelMessage(request);

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("한 구독자의 Redis 오류가 있어도 팬아웃 엔드포인트는 여전히 성공을 응답해야 한다")
                .isTrue();

        Double scoreA = redisTemplate.opsForZSet().score(rankKey(healthyClientA), "chatter1");
        Double scoreC = redisTemplate.opsForZSet().score(rankKey(healthyClientC), "chatter1");
        assertThat(scoreA)
                .as("건강한 구독자 A의 점수는 브로큰 구독자의 예외와 무관하게 정상 반영되어야 한다")
                .isEqualTo(1.0);
        assertThat(scoreC)
                .as("건강한 구독자 C의 점수도 브로큰 구독자의 예외와 무관하게 정상 반영되어야 한다")
                .isEqualTo(1.0);

        // brokenClientB의 키는 여전히 String 타입 그대로다 (증분은 실패했고, 예외는 격리되어
        // 팬아웃 자체를 막지 않았다).
        assertThat(redisTemplate.type(rankKey(brokenClientB)).name()).isEqualTo("STRING");
    }

    /**
     * 랭킹 키는 {@code chat:rank:{clientId}:{channelName}} — 클라이언트별로 하나이며,
     * 그 클라이언트가 구독한 "이후"에 도착한 채팅만 집계해야 한다.
     *
     * 이 성질은 두 가지 사실 위에 서 있다: (1) 팬아웃은 채팅이 도착한 시점에
     * {@code ChannelSubscriptionRegistry}의 현재 구독자 집합을 조회하므로, 구독 전에 지나간
     * 채팅은 그 클라이언트를 대상 목록에 넣지 않는다. (2) 구독은 레지스트리만 건드리고
     * Redis는 절대 건드리지 않으므로(랭킹 키는 구독 후 첫 ZINCRBY가 올 때 비로소 lazy하게
     * 생긴다), 늦게 들어온 클라이언트의 키는 그 전까지 아예 존재하지 않는다.
     *
     * 이 성질이 깨지면 예외 없이 그냥 숫자가 틀리게 나온다 — 그래서 명시적으로 검증해 둔다.
     */
    @Test
    void 늦게_구독한_클라이언트는_구독_이전_채팅을_집계하지_않는다() {
        // 이른 구독자만 채널에 있다.
        registry.add(channelName, earlySubscriber);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setChannelId(channelName);
        request.setChannelName(channelName);
        request.setUsername("late-join-chatter");

        // 늦은 구독자가 들어오기 전에 채팅 3건이 도착한다.
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> response = controller.addMultiChannelMessage(request);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        }

        // 아직 구독하지 않은 클라이언트의 랭킹 키는 존재조차 하면 안 된다 — 구독이 Redis를
        // 건드리지 않는다는 것과, 팬아웃이 구독 이전 채팅을 그 클라이언트에게 소급 적용하지
        // 않는다는 것을 함께 증명한다.
        assertThat(redisTemplate.hasKey(rankKey(lateSubscriber)))
                .as("구독 전에는 랭킹 키가 아예 존재하지 않아야 한다")
                .isFalse();

        // 늦은 구독자가 이제 들어온다 (레지스트리만 갱신, Redis는 여전히 손대지 않는다).
        registry.add(channelName, lateSubscriber);

        // 구독 이후 채팅 1건이 도착한다.
        ResponseEntity<String> lateResponse = controller.addMultiChannelMessage(request);
        assertThat(lateResponse.getStatusCode().is2xxSuccessful()).isTrue();

        Double earlyScore = redisTemplate.opsForZSet().score(rankKey(earlySubscriber), "late-join-chatter");
        Double lateScore = redisTemplate.opsForZSet().score(rankKey(lateSubscriber), "late-join-chatter");

        assertThat(earlyScore)
                .as("구독 시점부터 계속 있던 클라이언트는 4건(구독 전 3건 + 구독 후 1건) 모두 집계되어야 한다")
                .isEqualTo(4.0);
        assertThat(lateScore)
                .as("늦게 구독한 클라이언트는 자신이 구독한 이후의 채팅 1건만 집계되어야 한다")
                .isEqualTo(1.0);
    }
}

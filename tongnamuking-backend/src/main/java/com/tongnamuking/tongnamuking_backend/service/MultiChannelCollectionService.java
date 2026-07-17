package com.tongnamuking.tongnamuking_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 멀티채널 수집 조율.
 *
 * 프로세스를 직접 띄우지 않는다. 구독자 관리는 ChannelSubscriptionRegistry가,
 * 수집기 데몬 제어는 CollectorDaemonManager가 담당하고 여기서는 둘을 엮기만 한다.
 *
 * 채널의 첫 구독자가 들어올 때만 데몬에 구독을 걸고,
 * 마지막 구독자가 빠질 때만 해제한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MultiChannelCollectionService {

    private final ChannelSubscriptionRegistry registry;
    private final CollectorDaemonManager daemonManager;

    /** 클라이언트별 마지막 활동 시간 (핑 기반) */
    private final Map<String, Long> clientLastActivity = new ConcurrentHashMap<>();

    public boolean startCollection(String clientId, String channelId) {
        if (registry.isSubscribed(channelId, clientId)) {
            log.warn("클라이언트 {}에서 이미 수집 중인 채널입니다: {}", clientId, channelId);
            return false;
        }

        if (registry.countChannelsOf(clientId) >= registry.getMaxChannelsPerClient()) {
            log.warn("클라이언트 {}의 최대 수집기 수({})에 도달했습니다. 현재 수집 중인 채널: {}",
                    clientId, registry.getMaxChannelsPerClient(), registry.getChannelsOf(clientId));
            return false;
        }

        boolean isFirstSubscriber = registry.add(channelId, clientId);
        log.info("멀티채널 수집 시작: {} (클라이언트: {}, 첫 구독자: {})", channelId, clientId, isFirstSubscriber);

        if (!isFirstSubscriber) {
            // 이미 데몬이 해당 채널에 붙어 있다. 팬아웃 대상만 늘어난다.
            clientLastActivity.put(clientId, System.currentTimeMillis());
            return true;
        }

        boolean subscribed = daemonManager.subscribe(channelId);
        if (!subscribed) {
            // 롤백하지 않으면 "구독자는 있으나 커넥션은 없는" 유령 상태가 된다.
            // 이후 다른 클라이언트가 구독해도 첫 구독자가 아니라고 판정되어
            // 데몬을 호출하지 않으므로, 그 채널의 모든 구독자가 조용히 0건을 집계하게 된다.
            registry.remove(channelId, clientId);
            log.error("데몬 구독 실패로 롤백: {} (클라이언트: {})", channelId, clientId);
            return false;
        }

        clientLastActivity.put(clientId, System.currentTimeMillis());
        return true;
    }

    public boolean stopCollection(String clientId, String channelId) {
        if (!registry.isSubscribed(channelId, clientId)) {
            log.warn("클라이언트 {}에서 채널 {}은 수집 중이 아닙니다.", clientId, channelId);
            return false;
        }

        boolean wasLastSubscriber = registry.remove(channelId, clientId);
        if (wasLastSubscriber) {
            daemonManager.unsubscribe(channelId);
            log.info("마지막 구독자 이탈로 채널 해제: {}", channelId);
        }

        log.info("멀티채널 {} 수집 중지됨 (클라이언트: {})", channelId, clientId);
        return true;
    }

    public boolean stopAllCollections(String clientId) {
        for (String channelId : registry.getChannelsOf(clientId)) {
            stopCollection(clientId, channelId);
        }
        return true;
    }

    public boolean isCollecting(String clientId, String channelId) {
        return registry.isSubscribed(channelId, clientId);
    }

    public boolean isAnyCollecting(String clientId) {
        return registry.countChannelsOf(clientId) > 0;
    }

    public Set<String> getActiveChannels(String clientId) {
        return registry.getChannelsOf(clientId);
    }

    public int getActiveCollectorCount(String clientId) {
        return registry.countChannelsOf(clientId);
    }

    public int getMaxCollectors() {
        return registry.getMaxChannelsPerClient();
    }

    public String getStatus(String clientId) {
        Set<String> channels = registry.getChannelsOf(clientId);
        if (channels.isEmpty()) {
            return "수집 중인 채널 없음";
        }
        return String.format("수집 중인 채널: %d/%d - %s",
                channels.size(), registry.getMaxChannelsPerClient(), channels);
    }

    /**
     * 세션 활동 시간 업데이트 (핑 수신시 호출)
     */
    public void updateClientActivity(String clientId) {
        if (registry.countChannelsOf(clientId) > 0) {
            clientLastActivity.put(clientId, System.currentTimeMillis());
            log.debug("클라이언트 활동 업데이트: {}", clientId);
        }
    }

    /**
     * 30초마다 비활성 클라이언트의 구독 정리
     */
    @Scheduled(fixedRate = 30000)
    public void cleanupInactiveCilents() {
        long currentTime = System.currentTimeMillis();
        long inactiveThreshold = 2 * 60 * 1000; // 2분

        clientLastActivity.entrySet().removeIf(entry -> {
            String clientId = entry.getKey();
            long lastActivity = entry.getValue();

            if (currentTime - lastActivity > inactiveThreshold) {
                log.info("비활성 클라이언트 정리: {} ({}분 비활성)", clientId, (currentTime - lastActivity) / 60000);
                stopAllCollections(clientId);
                return true;
            }
            return false;
        });
    }
}

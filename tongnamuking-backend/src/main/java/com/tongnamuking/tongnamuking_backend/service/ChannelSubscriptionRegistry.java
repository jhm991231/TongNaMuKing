package com.tongnamuking.tongnamuking_backend.service;

import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 채널별 구독자 레지스트리.
 *
 * 누가 어떤 채널을 수집 중인지에 대한 유일한 진실이다.
 * 수집기 데몬은 clientId를 모르며, 채팅 팬아웃 대상은 전적으로 이 레지스트리가 결정한다.
 *
 * 역방향 맵(clientId -> channelId)은 두지 않는다. 맵이 둘이면 어긋날 수 있고,
 * 유니크 채널이 수십 개 규모이므로 순회 비용은 무시할 수 있다.
 */
@Service
public class ChannelSubscriptionRegistry {

    /** 사용자당 최대 동시 수집 채널 수 */
    private static final int MAX_CHANNELS_PER_CLIENT = 3;

    /** channelId -> clientId 집합. 값은 ConcurrentHashMap.newKeySet()이라 순회가 안전하다. */
    private final Map<String, Set<String>> channelSubscribers = new ConcurrentHashMap<>();

    /**
     * 구독자를 추가하고 첫 구독자였는지 반환한다.
     *
     * "추가"와 "이전에 비어 있었는지 판정"을 compute()로 원자적으로 수행한다.
     * containsKey 후 put으로 구현하면 동시 구독 시 두 클라이언트가 모두
     * 첫 구독자로 판정되어 데몬에 구독을 두 번 건다.
     */
    public boolean add(String channelId, String clientId) {
        boolean[] wasFirst = new boolean[1];
        channelSubscribers.compute(channelId, (key, subscribers) -> {
            if (subscribers == null) {
                wasFirst[0] = true;
                subscribers = ConcurrentHashMap.newKeySet();
            }
            subscribers.add(clientId);
            return subscribers;
        });
        return wasFirst[0];
    }

    /**
     * 구독자를 제거하고 마지막 구독자였는지 반환한다.
     * 집합이 비면 항목 자체를 제거해, 다음 구독이 첫 구독자로 판정되게 한다.
     */
    public boolean remove(String channelId, String clientId) {
        boolean[] wasLast = new boolean[1];
        channelSubscribers.compute(channelId, (key, subscribers) -> {
            if (subscribers == null) {
                return null;
            }
            boolean removed = subscribers.remove(clientId);
            if (removed && subscribers.isEmpty()) {
                wasLast[0] = true;
                return null;
            }
            return subscribers;
        });
        return wasLast[0];
    }

    /** 해당 채널의 구독자들. 팬아웃 대상이다. 없으면 빈 집합. */
    public Set<String> getSubscribers(String channelId) {
        Set<String> subscribers = channelSubscribers.get(channelId);
        return subscribers == null ? Set.of() : Set.copyOf(subscribers);
    }

    /** 해당 클라이언트가 구독 중인 채널들 */
    public Set<String> getChannelsOf(String clientId) {
        Set<String> channels = new HashSet<>();
        channelSubscribers.forEach((channelId, subscribers) -> {
            if (subscribers.contains(clientId)) {
                channels.add(channelId);
            }
        });
        return Set.copyOf(channels);
    }

    public int countChannelsOf(String clientId) {
        return getChannelsOf(clientId).size();
    }

    public boolean isSubscribed(String channelId, String clientId) {
        Set<String> subscribers = channelSubscribers.get(channelId);
        return subscribers != null && subscribers.contains(clientId);
    }

    /** 구독자가 하나 이상인 모든 채널. 데몬 재시작 후 재구독(replay)에 쓴다. */
    public Set<String> getAllChannels() {
        return Set.copyOf(channelSubscribers.keySet());
    }

    public int getMaxChannelsPerClient() {
        return MAX_CHANNELS_PER_CLIENT;
    }
}

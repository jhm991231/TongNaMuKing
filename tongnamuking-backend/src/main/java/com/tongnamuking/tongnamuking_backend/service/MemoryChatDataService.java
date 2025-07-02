package com.tongnamuking.tongnamuking_backend.service;

import com.tongnamuking.tongnamuking_backend.dto.ChatStatsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MemoryChatDataService {
    
    // 세션별 채팅 데이터 저장: sessionId -> channelName -> List<ChatData>
    private final Map<String, Map<String, List<ChatData>>> sessionChatData = new ConcurrentHashMap<>();
    
    // 세션별 마지막 활동 시간 추적
    private final Map<String, LocalDateTime> sessionLastActivity = new ConcurrentHashMap<>();
    
    // 메모리 관리 설정
    private static final int MAX_MESSAGES_PER_CHANNEL = 10000; // 채널당 최대 메시지 수
    private static final int MAX_MESSAGES_PER_SESSION = 50000; // 세션당 최대 메시지 수
    private static final int SESSION_TIMEOUT_HOURS = 24; // 세션 타임아웃 (시간)
    private static final int DATA_RETENTION_HOURS = 48; // 데이터 보관 시간
    
    public static class ChatData {
        public String username;
        public String message;
        public String channelName;
        public LocalDateTime timestamp;
        
        public ChatData(String username, String message, String channelName, LocalDateTime timestamp) {
            this.username = username;
            this.message = message;
            this.channelName = channelName;
            this.timestamp = timestamp;
        }
    }
    
    public void addChatMessage(String sessionId, String username, String channelName, String message) {
        log.debug("메모리에 채팅 추가: 세션={}, 채널={}, 사용자={}", sessionId, channelName, username);
        
        // 세션 활동 시간 업데이트
        sessionLastActivity.put(sessionId, LocalDateTime.now());
        
        // 세션별 데이터 맵 가져오기 또는 생성
        Map<String, List<ChatData>> channelData = sessionChatData.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
        
        // 채널별 채팅 리스트 가져오기 또는 생성
        List<ChatData> chatList = channelData.computeIfAbsent(channelName, k -> Collections.synchronizedList(new ArrayList<>()));
        
        // 메모리 사용량 체크 및 정리
        checkAndCleanupMemory(sessionId, channelName, chatList);
        
        // 새 채팅 데이터 추가
        ChatData newChat = new ChatData(username, message, channelName, LocalDateTime.now());
        chatList.add(newChat);
        
        log.debug("채팅 추가 완료: 세션 {} 채널 {} 총 {}개", sessionId, channelName, chatList.size());
    }
    
    private void checkAndCleanupMemory(String sessionId, String channelName, List<ChatData> chatList) {
        // 1. 채널당 메시지 수 제한
        if (chatList.size() >= MAX_MESSAGES_PER_CHANNEL) {
            int removeCount = chatList.size() - (MAX_MESSAGES_PER_CHANNEL * 3 / 4); // 25% 정리
            for (int i = 0; i < removeCount; i++) {
                chatList.remove(0); // 오래된 메시지부터 제거
            }
            log.info("채널 {} 메시지 정리: {}개 제거 (남은 메시지: {}개)", channelName, removeCount, chatList.size());
        }
        
        // 2. 세션당 총 메시지 수 제한
        int totalSessionMessages = getSessionChatCount(sessionId);
        if (totalSessionMessages >= MAX_MESSAGES_PER_SESSION) {
            cleanupOldestChannelData(sessionId);
            log.info("세션 {} 메시지 수 제한 도달: 오래된 채널 데이터 정리", sessionId);
        }
    }
    
    private void cleanupOldestChannelData(String sessionId) {
        Map<String, List<ChatData>> channelData = sessionChatData.get(sessionId);
        if (channelData == null || channelData.isEmpty()) {
            return;
        }
        
        // 가장 오래된 채널의 데이터 일부 제거
        String oldestChannel = channelData.entrySet().stream()
            .min((e1, e2) -> {
                LocalDateTime time1 = e1.getValue().isEmpty() ? LocalDateTime.now() : e1.getValue().get(0).timestamp;
                LocalDateTime time2 = e2.getValue().isEmpty() ? LocalDateTime.now() : e2.getValue().get(0).timestamp;
                return time1.compareTo(time2);
            })
            .map(Map.Entry::getKey)
            .orElse(null);
            
        if (oldestChannel != null) {
            List<ChatData> oldestChannelData = channelData.get(oldestChannel);
            int removeCount = oldestChannelData.size() / 2; // 50% 제거
            for (int i = 0; i < removeCount; i++) {
                oldestChannelData.remove(0);
            }
            log.info("가장 오래된 채널 {} 데이터 정리: {}개 제거", oldestChannel, removeCount);
        }
    }
    
    public List<ChatStatsResponse> getChatStatsByChannel(String sessionId, String channelName) {
        Map<String, List<ChatData>> channelData = sessionChatData.get(sessionId);
        if (channelData == null) {
            log.debug("세션 {} 데이터 없음", sessionId);
            return new ArrayList<>();
        }
        
        List<ChatData> chatList = channelData.get(channelName);
        if (chatList == null) {
            log.debug("세션 {} 채널 {} 데이터 없음", sessionId, channelName);
            return new ArrayList<>();
        }
        
        return calculateStats(chatList);
    }
    
    public List<ChatStatsResponse> getChatStatsByChannelAndTimeRange(String sessionId, String channelName, double hours) {
        Map<String, List<ChatData>> channelData = sessionChatData.get(sessionId);
        if (channelData == null) {
            return new ArrayList<>();
        }
        
        List<ChatData> chatList = channelData.get(channelName);
        if (chatList == null) {
            return new ArrayList<>();
        }
        
        // 시간 범위 필터링
        long minutes = Math.round(hours * 60);
        LocalDateTime startTime = LocalDateTime.now().minusMinutes(minutes);
        
        List<ChatData> filteredChats = chatList.stream()
                .filter(chat -> chat.timestamp.isAfter(startTime))
                .collect(Collectors.toList());
        
        log.debug("시간 범위 필터링: 전체 {}개 -> {}분 내 {}개", chatList.size(), minutes, filteredChats.size());
        
        return calculateStats(filteredChats);
    }
    
    private List<ChatStatsResponse> calculateStats(List<ChatData> chatList) {
        if (chatList.isEmpty()) {
            return new ArrayList<>();
        }
        
        // 사용자별 채팅 개수 계산
        Map<String, UserStats> userStatsMap = new HashMap<>();
        
        for (ChatData chat : chatList) {
            UserStats stats = userStatsMap.computeIfAbsent(chat.username, 
                k -> new UserStats(chat.username, chat.username));
            stats.messageCount++;
        }
        
        // 메시지 개수 기준으로 정렬
        List<UserStats> sortedStats = userStatsMap.values().stream()
                .sorted((a, b) -> Long.compare(b.messageCount, a.messageCount))
                .collect(Collectors.toList());
        
        // ChatStatsResponse로 변환
        List<ChatStatsResponse> responses = new ArrayList<>();
        for (int i = 0; i < sortedStats.size(); i++) {
            UserStats stats = sortedStats.get(i);
            responses.add(new ChatStatsResponse(
                (long) i, // userId (임시)
                stats.username,
                stats.displayName,
                stats.messageCount,
                i + 1 // rank
            ));
        }
        
        log.debug("통계 계산 완료: {}명의 사용자", responses.size());
        return responses;
    }
    
    private static class UserStats {
        String username;
        String displayName;
        long messageCount = 0;
        
        UserStats(String username, String displayName) {
            this.username = username;
            this.displayName = displayName;
        }
    }
    
    public void clearSessionData(String sessionId) {
        Map<String, List<ChatData>> removed = sessionChatData.remove(sessionId);
        sessionLastActivity.remove(sessionId);
        
        if (removed != null) {
            int totalMessages = removed.values().stream()
                    .mapToInt(List::size)
                    .sum();
            log.info("세션 {} 메모리 데이터 삭제: {}개 채널, 총 {}개 메시지", 
                    sessionId, removed.size(), totalMessages);
        } else {
            log.debug("세션 {} 메모리 데이터 없음", sessionId);
        }
    }
    
    @Scheduled(fixedRate = 300000) // 5분마다 실행
    public void cleanupInactiveSessionsAndOldData() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime sessionTimeoutThreshold = now.minusHours(SESSION_TIMEOUT_HOURS);
        LocalDateTime dataRetentionThreshold = now.minusHours(DATA_RETENTION_HOURS);
        
        // 1. 비활성 세션 정리
        Set<String> inactiveSessions = sessionLastActivity.entrySet().stream()
            .filter(entry -> entry.getValue().isBefore(sessionTimeoutThreshold))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
            
        for (String sessionId : inactiveSessions) {
            clearSessionData(sessionId);
            log.info("비활성 세션 {} 정리됨 (마지막 활동: {})", sessionId, sessionLastActivity.get(sessionId));
        }
        
        // 2. 오래된 메시지 데이터 정리
        int totalCleanedMessages = 0;
        for (Map.Entry<String, Map<String, List<ChatData>>> sessionEntry : sessionChatData.entrySet()) {
            String sessionId = sessionEntry.getKey();
            Map<String, List<ChatData>> channelData = sessionEntry.getValue();
            
            for (Map.Entry<String, List<ChatData>> channelEntry : channelData.entrySet()) {
                String channelName = channelEntry.getKey();
                List<ChatData> chatList = channelEntry.getValue();
                
                int beforeSize = chatList.size();
                chatList.removeIf(chat -> chat.timestamp.isBefore(dataRetentionThreshold));
                int afterSize = chatList.size();
                
                int cleaned = beforeSize - afterSize;
                if (cleaned > 0) {
                    totalCleanedMessages += cleaned;
                    log.debug("세션 {} 채널 {} 오래된 메시지 정리: {}개", sessionId, channelName, cleaned);
                }
            }
        }
        
        if (totalCleanedMessages > 0) {
            log.info("정기 정리 완료: 비활성 세션 {}개, 오래된 메시지 {}개 정리", 
                inactiveSessions.size(), totalCleanedMessages);
        }
        
        // 메모리 상태 로깅
        logMemoryStatus();
    }
    
    private void logMemoryStatus() {
        Map<String, Object> stats = getMemoryStats();
        log.info("메모리 상태 - 세션: {}개, 총 메시지: {}개", 
            stats.get("totalSessions"), stats.get("totalMessages"));
            
        // 메모리 사용량이 높으면 경고
        int totalMessages = (Integer) stats.get("totalMessages");
        if (totalMessages > 100000) {
            log.warn("⚠️ 메모리 사용량 높음: 총 {}개 메시지 저장됨", totalMessages);
        }
    }
    
    public void updateSessionActivity(String sessionId) {
        sessionLastActivity.put(sessionId, LocalDateTime.now());
        log.debug("세션 {} 활동 시간 업데이트", sessionId);
    }
    
    public int getSessionChatCount(String sessionId) {
        Map<String, List<ChatData>> channelData = sessionChatData.get(sessionId);
        if (channelData == null) {
            return 0;
        }
        
        return channelData.values().stream()
                .mapToInt(List::size)
                .sum();
    }
    
    public Set<String> getSessionChannels(String sessionId) {
        Map<String, List<ChatData>> channelData = sessionChatData.get(sessionId);
        return channelData != null ? channelData.keySet() : new HashSet<>();
    }
    
    public Map<String, Object> getMemoryStats() {
        int totalSessions = sessionChatData.size();
        int totalMessages = sessionChatData.values().stream()
                .flatMap(channelData -> channelData.values().stream())
                .mapToInt(List::size)
                .sum();
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime recentThreshold = now.minusHours(1);
        
        int activeSessions = (int) sessionLastActivity.entrySet().stream()
            .filter(entry -> entry.getValue().isAfter(recentThreshold))
            .count();
            
        return Map.of(
            "totalSessions", totalSessions,
            "activeSessions", activeSessions,
            "totalMessages", totalMessages,
            "memoryLimits", Map.of(
                "maxMessagesPerChannel", MAX_MESSAGES_PER_CHANNEL,
                "maxMessagesPerSession", MAX_MESSAGES_PER_SESSION,
                "sessionTimeoutHours", SESSION_TIMEOUT_HOURS,
                "dataRetentionHours", DATA_RETENTION_HOURS
            ),
            "sessionsWithData", sessionChatData.entrySet().stream()
                    .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().values().stream().mapToInt(List::size).sum()
                    ))
        );
    }
}
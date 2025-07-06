package com.tongnamuking.tongnamuking_backend.service;

import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

@Service
@Slf4j
public class MultiChannelCollectionService {
    
    // 세션별 수집기 관리: sessionId -> (channelId -> Process)
    private final Map<String, Map<String, Process>> userCollections = new ConcurrentHashMap<>();
    
    // 세션별 마지막 활동 시간 (핑 기반)
    private final Map<String, Long> sessionLastActivity = new ConcurrentHashMap<>();
    
    private final int MAX_COLLECTORS_PER_USER = 3;
    
    public boolean startCollection(String clientId, String channelId) {
        
        // 사용자별 수집기 맵 가져오기 또는 생성
        Map<String, Process> userChannels = userCollections.computeIfAbsent(clientId, k -> new ConcurrentHashMap<>());
        
        // 이미 해당 사용자가 해당 채널을 수집 중인지 확인
        if (userChannels.containsKey(channelId)) {
            log.warn("클라이언트 {}에서 이미 수집 중인 채널입니다: {}", clientId, channelId);
            return false;
        }
        
        // 사용자별 최대 수집기 수 확인
        if (userChannels.size() >= MAX_COLLECTORS_PER_USER) {
            log.warn("클라이언트 {}의 최대 수집기 수({})에 도달했습니다. 현재 수집 중인 채널: {}", 
                    clientId, MAX_COLLECTORS_PER_USER, userChannels.keySet());
            return false;
        }
        
        try {
            log.info("멀티채널 수집 시작: {} (클라이언트: {}, 현재 활성 수집기: {})", 
                    channelId, clientId, userChannels.size());
            
            // Node.js 채팅 수집기 실행
            ProcessBuilder processBuilder = new ProcessBuilder();
            
            // 개발/운영 환경에 따른 경로 설정
            String os = System.getProperty("os.name").toLowerCase();
            String nodeCommand = "node"; // PATH에서 node 찾기
            String scriptPath = os.contains("win") ? 
                "C:\\Users\\jhm99\\vscode_workspace\\TongNaMuKing\\chat-collector\\index.js" : 
                "/app/chat-collector/index.js";
            String workingDir = os.contains("win") ? 
                "C:\\Users\\jhm99\\vscode_workspace\\TongNaMuKing" : 
                "/app";
            
            processBuilder.command(nodeCommand, scriptPath, channelId, clientId);
            processBuilder.directory(new java.io.File(workingDir));
            processBuilder.redirectErrorStream(true);
            
            Process process = processBuilder.start();
            userChannels.put(channelId, process);
            
            // 세션 활동 시간 초기화
            sessionLastActivity.put(clientId, System.currentTimeMillis());
            
            // 비동기로 프로세스 출력 로깅
            CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[MultiChannel-{}] {}", channelId.substring(0, 8), line);
                    }
                } catch (IOException e) {
                    log.error("채널 {} 수집기 출력 읽기 실패", channelId, e);
                }
            });
            
            // 프로세스 종료 감지
            CompletableFuture.runAsync(() -> {
                try {
                    int exitCode = process.waitFor();
                    log.info("멀티채널 {} 수집기 종료됨. Exit code: {}", channelId, exitCode);
                    userChannels.remove(channelId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("채널 {} 프로세스 대기 중 인터럽트", channelId, e);
                    userChannels.remove(channelId);
                }
            });
            
            return true;
            
        } catch (IOException e) {
            log.error("채널 {} 수집 시작 실패", channelId, e);
            userChannels.remove(channelId);
            return false;
        }
    }
    
    public boolean stopCollection(String clientId, String channelId) {
        Map<String, Process> userChannels = userCollections.get(clientId);
        if (userChannels == null) {
            log.warn("클라이언트 {}에 수집기가 없습니다.", clientId);
            return false;
        }
        
        Process process = userChannels.get(channelId);
        if (process == null) {
            log.warn("클라이언트 {}에서 채널 {}은 수집 중이 아닙니다.", clientId, channelId);
            return false;
        }
        
        try {
            if (process.isAlive()) {
                long pid = process.pid();
                log.info("채널 {} 프로세스 종료 시작 (PID: {})", channelId, pid);
                
                // Java 기본 프로세스 종료 사용 (크로스 플랫폼 호환)
                log.info("Java 기본 방법으로 프로세스 {} 강제 종료", pid);
                process.destroyForcibly();
                
                // 프로세스 종료 대기 (최대 5초)
                boolean terminated = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (terminated) {
                    log.info("채널 {} 프로세스 정상 종료됨", channelId);
                } else {
                    log.warn("채널 {} 프로세스 종료 타임아웃 (5초)", channelId);
                }
                
                log.info("멀티채널 {} 수집 중지됨 (클라이언트: {})", channelId, clientId);
            }
            
            userChannels.remove(channelId);
            return true;
            
        } catch (Exception e) {
            log.error("채널 {} 프로세스 종료 중 오류", channelId, e);
            userChannels.remove(channelId);
            return false;
        }
    }
    
    public boolean stopAllCollections(String clientId) {
        Map<String, Process> userChannels = userCollections.get(clientId);
        if (userChannels == null || userChannels.isEmpty()) {
            return true;
        }
        
        boolean allStopped = true;
        for (String channelId : Set.copyOf(userChannels.keySet())) {
            if (!stopCollection(clientId, channelId)) {
                allStopped = false;
            }
        }
        return allStopped;
    }
    
    public boolean isCollecting(String clientId, String channelId) {
        Map<String, Process> userChannels = userCollections.get(clientId);
        return userChannels != null && userChannels.containsKey(channelId);
    }
    
    public boolean isAnyCollecting(String clientId) {
        Map<String, Process> userChannels = userCollections.get(clientId);
        return userChannels != null && !userChannels.isEmpty();
    }
    
    public Set<String> getActiveChannels(String clientId) {
        Map<String, Process> userChannels = userCollections.get(clientId);
        return userChannels != null ? Set.copyOf(userChannels.keySet()) : new HashSet<>();
    }
    
    public int getActiveCollectorCount(String clientId) {
        Map<String, Process> userChannels = userCollections.get(clientId);
        return userChannels != null ? userChannels.size() : 0;
    }
    
    public int getMaxCollectors() {
        return MAX_COLLECTORS_PER_USER;
    }
    
    public String getStatus(String clientId) {
        Map<String, Process> userChannels = userCollections.get(clientId);
        if (userChannels == null || userChannels.isEmpty()) {
            return "수집 중인 채널 없음";
        } else {
            return String.format("수집 중인 채널: %d/%d - %s", 
                    userChannels.size(), MAX_COLLECTORS_PER_USER, userChannels.keySet());
        }
    }
    
    /**
     * 세션 활동 시간 업데이트 (핑 수신시 호출)
     */
    public void updateSessionActivity(String clientId) {
        if (userCollections.containsKey(clientId)) {
            sessionLastActivity.put(clientId, System.currentTimeMillis());
            log.debug("클라이언트 활동 업데이트: {}", clientId);
        }
    }
    
    /**
     * 30초마다 비활성 세션의 chat-collector 정리
     */
    @Scheduled(fixedRate = 30000)
    public void cleanupInactiveSessions() {
        long currentTime = System.currentTimeMillis();
        long inactiveThreshold = 2 * 60 * 1000; // 2분
        
        sessionLastActivity.entrySet().removeIf(entry -> {
            String clientId = entry.getKey();
            long lastActivity = entry.getValue();
            
            if (currentTime - lastActivity > inactiveThreshold) {
                log.info("비활성 클라이언트 정리: {} ({}분 비활성)", clientId, (currentTime - lastActivity) / 60000);
                
                // 해당 세션의 모든 chat-collector 종료
                stopAllCollections(clientId);
                
                // 세션 데이터 정리
                userCollections.remove(clientId);
                
                return true; // Map에서 제거
            }
            return false; // 유지
        });
    }
}
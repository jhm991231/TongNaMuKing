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
    private static final String DOGCAKE_CHANNEL_ID = "b68af124ae2f1743a1dcbf5e2ab41e0b";
    
    public boolean startCollection(String sessionId, String channelId) {
        // 독케익 채널은 멀티채널 시스템에서 수집하지 않음
        if (DOGCAKE_CHANNEL_ID.equals(channelId)) {
            log.warn("독케익 채널은 전용 시스템에서 수집됩니다: {}", channelId);
            return false;
        }
        
        // 사용자별 수집기 맵 가져오기 또는 생성
        Map<String, Process> userChannels = userCollections.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
        
        // 이미 해당 사용자가 해당 채널을 수집 중인지 확인
        if (userChannels.containsKey(channelId)) {
            log.warn("세션 {}에서 이미 수집 중인 채널입니다: {}", sessionId, channelId);
            return false;
        }
        
        // 사용자별 최대 수집기 수 확인
        if (userChannels.size() >= MAX_COLLECTORS_PER_USER) {
            log.warn("세션 {}의 최대 수집기 수({})에 도달했습니다. 현재 수집 중인 채널: {}", 
                    sessionId, MAX_COLLECTORS_PER_USER, userChannels.keySet());
            return false;
        }
        
        try {
            log.info("멀티채널 수집 시작: {} (세션: {}, 현재 활성 수집기: {})", 
                    channelId, sessionId, userChannels.size());
            
            // Node.js 채팅 수집기 실행
            ProcessBuilder processBuilder = new ProcessBuilder();
            
            // 개발/운영 환경에 따른 경로 설정
            String os = System.getProperty("os.name").toLowerCase();
            String nodeCommand = os.contains("win") ? "node" : "/usr/bin/node";
            String scriptPath = os.contains("win") ? 
                "C:\\Users\\jhm99\\vscode_workspace\\TongNaMuKing\\chat-collector\\index.js" : 
                "/app/chat-collector/index.js";
            String workingDir = os.contains("win") ? 
                "C:\\Users\\jhm99\\vscode_workspace\\TongNaMuKing" : 
                "/app";
            
            processBuilder.command(nodeCommand, scriptPath, channelId, sessionId);
            processBuilder.directory(new java.io.File(workingDir));
            processBuilder.redirectErrorStream(true);
            
            Process process = processBuilder.start();
            userChannels.put(channelId, process);
            
            // 세션 활동 시간 초기화
            sessionLastActivity.put(sessionId, System.currentTimeMillis());
            
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
    
    public boolean stopCollection(String sessionId, String channelId) {
        Map<String, Process> userChannels = userCollections.get(sessionId);
        if (userChannels == null) {
            log.warn("세션 {}에 수집기가 없습니다.", sessionId);
            return false;
        }
        
        Process process = userChannels.get(channelId);
        if (process == null) {
            log.warn("세션 {}에서 채널 {}은 수집 중이 아닙니다.", sessionId, channelId);
            return false;
        }
        
        try {
            if (process.isAlive()) {
                long pid = process.pid();
                log.info("채널 {} 프로세스 종료 시작 (PID: {})", channelId, pid);
                
                // 직접 시스템 명령어로 강제 종료 (WSL에서 Java 시그널이 제대로 작동하지 않음)
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("linux") || os.contains("unix")) {
                    log.info("시스템 명령어로 프로세스 {} 강제 종료", pid);
                    
                    // PID로 직접 강제 종료
                    ProcessBuilder killPid = new ProcessBuilder("kill", "-9", String.valueOf(pid));
                    Process killProcess = killPid.start();
                    killProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                    
                    // 추가 안전장치: 관련 프로세스들도 정리
                    ProcessBuilder killCmd1 = new ProcessBuilder("pkill", "-9", "-f", channelId);
                    killCmd1.start().waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                    
                    ProcessBuilder killCmd2 = new ProcessBuilder("pkill", "-9", "-f", sessionId);
                    killCmd2.start().waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                    
                    log.info("채널 {} 시스템 레벨 강제 종료 완료", channelId);
                } else {
                    // Windows에서는 기존 방식 사용
                    process.destroyForcibly();
                    process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                }
                
                log.info("멀티채널 {} 수집 중지됨 (세션: {})", channelId, sessionId);
            }
            
            userChannels.remove(channelId);
            return true;
            
        } catch (Exception e) {
            log.error("채널 {} 프로세스 종료 중 오류", channelId, e);
            userChannels.remove(channelId);
            return false;
        }
    }
    
    public boolean stopAllCollections(String sessionId) {
        Map<String, Process> userChannels = userCollections.get(sessionId);
        if (userChannels == null || userChannels.isEmpty()) {
            return true;
        }
        
        boolean allStopped = true;
        for (String channelId : Set.copyOf(userChannels.keySet())) {
            if (!stopCollection(sessionId, channelId)) {
                allStopped = false;
            }
        }
        return allStopped;
    }
    
    public boolean isCollecting(String sessionId, String channelId) {
        Map<String, Process> userChannels = userCollections.get(sessionId);
        return userChannels != null && userChannels.containsKey(channelId);
    }
    
    public boolean isAnyCollecting(String sessionId) {
        Map<String, Process> userChannels = userCollections.get(sessionId);
        return userChannels != null && !userChannels.isEmpty();
    }
    
    public Set<String> getActiveChannels(String sessionId) {
        Map<String, Process> userChannels = userCollections.get(sessionId);
        return userChannels != null ? Set.copyOf(userChannels.keySet()) : new HashSet<>();
    }
    
    public int getActiveCollectorCount(String sessionId) {
        Map<String, Process> userChannels = userCollections.get(sessionId);
        return userChannels != null ? userChannels.size() : 0;
    }
    
    public int getMaxCollectors() {
        return MAX_COLLECTORS_PER_USER;
    }
    
    public String getStatus(String sessionId) {
        Map<String, Process> userChannels = userCollections.get(sessionId);
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
    public void updateSessionActivity(String sessionId) {
        if (userCollections.containsKey(sessionId)) {
            sessionLastActivity.put(sessionId, System.currentTimeMillis());
            log.debug("세션 활동 업데이트: {}", sessionId);
        }
    }
    
    /**
     * 30초마다 비활성 세션의 chat-collector 정리
     */
    @Scheduled(fixedRate = 30000)
    public void cleanupInactiveSessions() {
        long currentTime = System.currentTimeMillis();
        long inactiveThreshold = 1 * 60 * 1000; // 1분 (테스트용)
        
        sessionLastActivity.entrySet().removeIf(entry -> {
            String sessionId = entry.getKey();
            long lastActivity = entry.getValue();
            
            if (currentTime - lastActivity > inactiveThreshold) {
                log.info("비활성 세션 정리: {} ({}분 비활성)", sessionId, (currentTime - lastActivity) / 60000);
                
                // 해당 세션의 모든 chat-collector 종료
                stopAllCollections(sessionId);
                
                // 세션 데이터 정리
                userCollections.remove(sessionId);
                
                return true; // Map에서 제거
            }
            return false; // 유지
        });
    }
}
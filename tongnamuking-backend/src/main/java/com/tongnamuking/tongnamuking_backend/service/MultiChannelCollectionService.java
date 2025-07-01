package com.tongnamuking.tongnamuking_backend.service;

import org.springframework.stereotype.Service;
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
    private final int MAX_COLLECTORS_PER_USER = 3;
    private static final String DOGCAKE_CHANNEL_ID = "9c0c6780aa8f2a7d70c4bf2bb3c292c9";
    
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
            
            processBuilder.command(nodeCommand, scriptPath, channelId);
            processBuilder.directory(new java.io.File(workingDir));
            processBuilder.redirectErrorStream(true);
            
            Process process = processBuilder.start();
            userChannels.put(channelId, process);
            
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
                process.destroyForcibly();
                process.waitFor();
                log.info("멀티채널 {} 수집 중지됨 (세션: {})", channelId, sessionId);
            }
            
            userChannels.remove(channelId);
            return true;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("채널 {} 프로세스 종료 대기 중 인터럽트", channelId, e);
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
}
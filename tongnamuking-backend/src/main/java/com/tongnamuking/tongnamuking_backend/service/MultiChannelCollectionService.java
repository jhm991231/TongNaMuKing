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

@Service
@Slf4j
public class MultiChannelCollectionService {
    
    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private final int MAX_COLLECTORS = 3;
    private static final String DOGCAKE_CHANNEL_ID = "9c0c6780aa8f2a7d70c4bf2bb3c292c9";
    
    public boolean startCollection(String channelId) {
        // 독케익 채널은 멀티채널 시스템에서 수집하지 않음
        if (DOGCAKE_CHANNEL_ID.equals(channelId)) {
            log.warn("독케익 채널은 전용 시스템에서 수집됩니다: {}", channelId);
            return false;
        }
        
        // 이미 해당 채널이 수집 중인지 확인
        if (activeProcesses.containsKey(channelId)) {
            log.warn("이미 수집 중인 채널입니다: {}", channelId);
            return false;
        }
        
        // 최대 수집기 수 확인
        if (activeProcesses.size() >= MAX_COLLECTORS) {
            log.warn("최대 수집기 수({})에 도달했습니다. 현재 수집 중인 채널: {}", 
                    MAX_COLLECTORS, activeProcesses.keySet());
            return false;
        }
        
        try {
            log.info("멀티채널 수집 시작: {} (현재 활성 수집기: {})", 
                    channelId, activeProcesses.size());
            
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
            activeProcesses.put(channelId, process);
            
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
                    activeProcesses.remove(channelId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("채널 {} 프로세스 대기 중 인터럽트", channelId, e);
                    activeProcesses.remove(channelId);
                }
            });
            
            return true;
            
        } catch (IOException e) {
            log.error("채널 {} 수집 시작 실패", channelId, e);
            activeProcesses.remove(channelId);
            return false;
        }
    }
    
    public boolean stopCollection(String channelId) {
        Process process = activeProcesses.get(channelId);
        if (process == null) {
            log.warn("채널 {}은 수집 중이 아닙니다.", channelId);
            return false;
        }
        
        try {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
                log.info("멀티채널 {} 수집 중지됨", channelId);
            }
            
            activeProcesses.remove(channelId);
            return true;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("채널 {} 프로세스 종료 대기 중 인터럽트", channelId, e);
            activeProcesses.remove(channelId);
            return false;
        }
    }
    
    public boolean stopAllCollections() {
        boolean allStopped = true;
        for (String channelId : Set.copyOf(activeProcesses.keySet())) {
            if (!stopCollection(channelId)) {
                allStopped = false;
            }
        }
        return allStopped;
    }
    
    public boolean isCollecting(String channelId) {
        return activeProcesses.containsKey(channelId);
    }
    
    public boolean isAnyCollecting() {
        return !activeProcesses.isEmpty();
    }
    
    public Set<String> getActiveChannels() {
        return Set.copyOf(activeProcesses.keySet());
    }
    
    public int getActiveCollectorCount() {
        return activeProcesses.size();
    }
    
    public int getMaxCollectors() {
        return MAX_COLLECTORS;
    }
    
    public String getStatus() {
        if (activeProcesses.isEmpty()) {
            return "수집 중인 채널 없음";
        } else {
            return String.format("수집 중인 채널: %d/%d - %s", 
                    activeProcesses.size(), MAX_COLLECTORS, activeProcesses.keySet());
        }
    }
}
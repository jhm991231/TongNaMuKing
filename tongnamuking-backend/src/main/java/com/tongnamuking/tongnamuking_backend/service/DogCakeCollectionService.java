package com.tongnamuking.tongnamuking_backend.service;

import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class DogCakeCollectionService {
    
    private static final String DOGCAKE_CHANNEL_ID = "b68af124ae2f1743a1dcbf5e2ab41e0b";
    private Process dogCakeProcess;
    
    public boolean startDogCakeCollection() {
        // 이미 독케익 수집 중인지 확인
        if (dogCakeProcess != null && dogCakeProcess.isAlive()) {
            log.warn("독케익 채팅 수집이 이미 실행 중입니다.");
            return false;
        }
        
        try {
            log.info("독케익 채팅 수집 시작");
            
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
            
            // 독케익 전용 세션 ID 생성
            String sessionId = "DOGCAKE_SESSION";
            processBuilder.command(nodeCommand, scriptPath, DOGCAKE_CHANNEL_ID, sessionId);
            processBuilder.directory(new java.io.File(workingDir));
            processBuilder.redirectErrorStream(true);
            
            dogCakeProcess = processBuilder.start();
            
            // 비동기로 프로세스 출력 로깅
            CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(dogCakeProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[DogCake-Collector] {}", line);
                    }
                } catch (IOException e) {
                    log.error("독케익 수집기 출력 읽기 실패", e);
                }
            });
            
            // 프로세스 종료 감지
            CompletableFuture.runAsync(() -> {
                try {
                    int exitCode = dogCakeProcess.waitFor();
                    log.info("독케익 수집기 종료됨. Exit code: {}", exitCode);
                    dogCakeProcess = null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("독케익 프로세스 대기 중 인터럽트", e);
                    dogCakeProcess = null;
                }
            });
            
            return true;
            
        } catch (IOException e) {
            log.error("독케익 수집 시작 실패", e);
            dogCakeProcess = null;
            return false;
        }
    }
    
    public boolean stopDogCakeCollection() {
        if (dogCakeProcess == null || !dogCakeProcess.isAlive()) {
            log.warn("독케익 수집이 실행 중이 아닙니다.");
            return false;
        }
        
        try {
            dogCakeProcess.destroyForcibly();
            dogCakeProcess.waitFor();
            log.info("독케익 수집 중지됨");
            dogCakeProcess = null;
            return true;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("독케익 프로세스 종료 대기 중 인터럽트", e);
            dogCakeProcess = null;
            return false;
        }
    }
    
    public boolean isDogCakeCollecting() {
        return dogCakeProcess != null && dogCakeProcess.isAlive();
    }
    
    public String getDogCakeChannelId() {
        return DOGCAKE_CHANNEL_ID;
    }
    
    public String getStatus() {
        return isDogCakeCollecting() ? "독케익 수집 중" : "독케익 수집 중지됨";
    }
}
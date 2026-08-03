package com.tongnamuking.tongnamuking_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class DogCakeCollectionService {
    
    private static final String DOGCAKE_CHANNEL_ID = "b68af124ae2f1743a1dcbf5e2ab41e0b";
    private Process dogCakeProcess;

    @Value("${dogcake.collector.script:/app/chat-collector/index.js}")
    private String scriptPath;

    /** 수집기를 띄울 명령을 조립한다. 실행은 하지 않는다. */
    ProcessBuilder buildCollectorProcess(String clientId) {
        // 설정값이 상대 경로여도 되도록 절대 경로로 푼다 (JVM 작업 디렉터리 기준)
        File script = new File(scriptPath).getAbsoluteFile();

        ProcessBuilder processBuilder = new ProcessBuilder(
                "node", script.getAbsolutePath(), DOGCAKE_CHANNEL_ID, clientId);
        // 실행 위치를 스크립트 폴더로 고정한다. 지정하지 않으면 JVM 의 작업 디렉터리를
        // 물려받아 IntelliJ(backend/) 와 Docker(/app) 에서 서로 달라진다.
        processBuilder.directory(script.getParentFile());
        processBuilder.redirectErrorStream(true);
        return processBuilder;
    }

    public boolean startDogCakeCollection() {
        // 이미 독케익 수집 중인지 확인
        if (dogCakeProcess != null && dogCakeProcess.isAlive()) {
            log.warn("독케익 채팅 수집이 이미 실행 중입니다.");
            return false;
        }
        
        try {
            log.info("독케익 채팅 수집 시작");

            // 독케익 전용 세션 ID
            ProcessBuilder processBuilder = buildCollectorProcess("DOGCAKE_SESSION");
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
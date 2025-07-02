package com.tongnamuking.tongnamuking_backend.controller;

import com.tongnamuking.tongnamuking_backend.service.MultiChannelCollectionService;
import com.tongnamuking.tongnamuking_backend.service.DogCakeCollectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/process-cleanup")
@RequiredArgsConstructor
@Slf4j
public class ProcessCleanupController {
    
    private final MultiChannelCollectionService multiChannelCollectionService;
    private final DogCakeCollectionService dogCakeCollectionService;
    
    @PostMapping("/stop-all-collectors")
    public ResponseEntity<Map<String, Object>> stopAllCollectors(HttpSession session) {
        try {
            log.info("안전한 방식으로 모든 수집기 중지 시작");
            
            // 1. 멀티채널 수집기들 안전하게 중지
            boolean multiChannelStopped = multiChannelCollectionService.stopAllCollections(session.getId());
            
            // 2. 독케익 수집기 안전하게 중지
            boolean dogCakeStopped = dogCakeCollectionService.stopDogCakeCollection();
            
            boolean allStopped = multiChannelStopped && dogCakeStopped;
            
            String message = allStopped ? 
                "모든 채팅 수집기가 안전하게 중지되었습니다" : 
                "일부 수집기 중지에 실패했습니다. 다시 시도해주세요";
                
            log.info("수집기 중지 결과 - 멀티채널: {}, 독케익: {}", multiChannelStopped, dogCakeStopped);
            
            return ResponseEntity.ok(Map.of(
                "success", allStopped,
                "message", message,
                "details", Map.of(
                    "multiChannelStopped", multiChannelStopped,
                    "dogCakeStopped", dogCakeStopped
                )
            ));
            
        } catch (Exception e) {
            log.error("수집기 중지 실패", e);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "수집기 중지 중 오류가 발생했습니다: " + e.getMessage()
            ));
        }
    }
    
    @PostMapping("/force-kill-collectors")
    public ResponseEntity<Map<String, Object>> forceKillCollectors() {
        try {
            log.warn("강제 프로세스 종료 시작 - 주의: 데이터 손실 가능성 있음");
            
            String os = System.getProperty("os.name").toLowerCase();
            boolean success = false;
            String message;
            
            if (os.contains("win")) {
                // Windows - taskkill 사용
                ProcessBuilder killCmd = new ProcessBuilder("taskkill", "/F", "/IM", "node.exe");
                Process killProcess = killCmd.start();
                success = killProcess.waitFor(5, TimeUnit.SECONDS);
                message = success ? "Windows에서 Node.js 프로세스 강제 종료 완료" : "Windows 프로세스 종료 시간 초과";
                
            } else {
                // Linux/Unix - 더 안전한 방식으로 변경
                ProcessBuilder killCmd = new ProcessBuilder("pkill", "-f", "chat-collector.*index.js");
                Process killProcess = killCmd.start();
                success = killProcess.waitFor(5, TimeUnit.SECONDS);
                message = success ? "Linux에서 chat-collector 프로세스 종료 완료" : "Linux 프로세스 종료 시간 초과";
            }
            
            log.info("강제 프로세스 종료 결과: {}", message);
            
            return ResponseEntity.ok(Map.of(
                "success", success,
                "message", message,
                "warning", "강제 종료로 인해 데이터 손실이 있을 수 있습니다"
            ));
            
        } catch (Exception e) {
            log.error("강제 프로세스 종료 실패", e);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "강제 종료 실패: " + e.getMessage()
            ));
        }
    }
    
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getCollectorStatus(HttpSession session) {
        try {
            // 서비스별 상태 조회
            String multiChannelStatus = multiChannelCollectionService.getStatus(session.getId());
            String dogCakeStatus = dogCakeCollectionService.getStatus();
            
            boolean multiChannelActive = multiChannelCollectionService.isAnyCollecting(session.getId());
            boolean dogCakeActive = dogCakeCollectionService.isDogCakeCollecting();
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "multiChannel", Map.of(
                    "status", multiChannelStatus,
                    "isActive", multiChannelActive,
                    "activeChannels", multiChannelCollectionService.getActiveChannels(session.getId()),
                    "count", multiChannelCollectionService.getActiveCollectorCount(session.getId())
                ),
                "dogCake", Map.of(
                    "status", dogCakeStatus,
                    "isActive", dogCakeActive,
                    "channelId", dogCakeCollectionService.getDogCakeChannelId()
                )
            ));
            
        } catch (Exception e) {
            log.error("수집기 상태 조회 실패", e);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "상태 조회 실패: " + e.getMessage()
            ));
        }
    }
}
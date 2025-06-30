package com.tongnamuking.tongnamuking_backend.controller;

import com.tongnamuking.tongnamuking_backend.service.NodejsChatCollectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/nodejs-chat-collection")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class NodejsChatController {
    
    private final NodejsChatCollectionService nodejsChatCollectionService;
    
    @PostMapping("/start/{channelId}")
    public ResponseEntity<Map<String, Object>> startCollection(@PathVariable String channelId) {
        boolean success = nodejsChatCollectionService.startCollection(channelId);
        
        String message;
        if (success) {
            message = "Node.js 채팅 수집이 시작되었습니다";
        } else if (nodejsChatCollectionService.isCollecting(channelId)) {
            message = "이미 해당 채널의 채팅을 수집 중입니다";
        } else if (nodejsChatCollectionService.getActiveCollectorCount() >= nodejsChatCollectionService.getMaxCollectors()) {
            message = String.format("최대 수집기 수(%d)에 도달했습니다", nodejsChatCollectionService.getMaxCollectors());
        } else {
            message = "Node.js 채팅 수집 시작에 실패했습니다";
        }
        
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", message,
            "status", nodejsChatCollectionService.getStatus(),
            "activeChannels", nodejsChatCollectionService.getActiveChannels(),
            "activeCount", nodejsChatCollectionService.getActiveCollectorCount(),
            "maxCount", nodejsChatCollectionService.getMaxCollectors()
        ));
    }
    
    @PostMapping("/stop/{channelId}")
    public ResponseEntity<Map<String, Object>> stopCollection(@PathVariable String channelId) {
        boolean success = nodejsChatCollectionService.stopCollection(channelId);
        
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", success ? "Node.js 채팅 수집이 중지되었습니다" : "해당 채널은 수집 중이 아닙니다",
            "status", nodejsChatCollectionService.getStatus(),
            "activeChannels", nodejsChatCollectionService.getActiveChannels(),
            "activeCount", nodejsChatCollectionService.getActiveCollectorCount()
        ));
    }
    
    @PostMapping("/stop-all")
    public ResponseEntity<Map<String, Object>> stopAllCollections() {
        boolean success = nodejsChatCollectionService.stopAllCollections();
        
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", success ? "모든 채팅 수집이 중지되었습니다" : "일부 채팅 수집 중지에 실패했습니다",
            "status", nodejsChatCollectionService.getStatus()
        ));
    }
    
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
            "isAnyCollecting", nodejsChatCollectionService.isAnyCollecting(),
            "activeChannels", nodejsChatCollectionService.getActiveChannels(),
            "activeCount", nodejsChatCollectionService.getActiveCollectorCount(),
            "maxCount", nodejsChatCollectionService.getMaxCollectors(),
            "status", nodejsChatCollectionService.getStatus()
        ));
    }
    
    @GetMapping("/status/{channelId}")
    public ResponseEntity<Map<String, Object>> getChannelStatus(@PathVariable String channelId) {
        return ResponseEntity.ok(Map.of(
            "channelId", channelId,
            "isCollecting", nodejsChatCollectionService.isCollecting(channelId)
        ));
    }
}
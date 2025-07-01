package com.tongnamuking.tongnamuking_backend.controller;

import com.tongnamuking.tongnamuking_backend.dto.ChatStatsResponse;
import com.tongnamuking.tongnamuking_backend.dto.ChatDogRatioResponse;
import com.tongnamuking.tongnamuking_backend.dto.ManualGameSegmentRequest;
import com.tongnamuking.tongnamuking_backend.service.ChatStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat-stats")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatStatsController {
    
    private final ChatStatsService chatStatsService;
    
    @GetMapping("/channel/{channelName}")
    public ResponseEntity<List<ChatStatsResponse>> getChatStatsByChannel(
            @PathVariable String channelName,
            @RequestParam(defaultValue = "0") int hours) {
        
        List<ChatStatsResponse> stats;
        if (hours > 0) {
            stats = chatStatsService.getChatStatsByChannelAndTimeRange(channelName, hours);
        } else {
            stats = chatStatsService.getChatStatsByChannel(channelName);
        }
        
        return ResponseEntity.ok(stats);
    }
    
    @GetMapping("/channel/{channelName}/top/{limit}")
    public ResponseEntity<List<ChatStatsResponse>> getTopChatters(
            @PathVariable String channelName,
            @PathVariable int limit,
            @RequestParam(defaultValue = "0") int hours) {
        
        List<ChatStatsResponse> stats;
        if (hours > 0) {
            stats = chatStatsService.getChatStatsByChannelAndTimeRange(channelName, hours);
        } else {
            stats = chatStatsService.getChatStatsByChannel(channelName);
        }
        
        return ResponseEntity.ok(stats.subList(0, Math.min(limit, stats.size())));
    }
    
    @GetMapping("/chatdog-ratio/{channelName}")
    public ResponseEntity<ChatDogRatioResponse> getChatDogRatio(
            @PathVariable String channelName,
            @RequestParam(defaultValue = "120") int justChatDuration,
            @RequestParam(defaultValue = "false") boolean useManualTime) {
        
        ChatDogRatioResponse ratio = chatStatsService.calculateChatDogRatio(channelName, justChatDuration, useManualTime);
        return ResponseEntity.ok(ratio);
    }
    
    @PostMapping("/chatdog-ratio/{channelName}/manual")
    public ResponseEntity<ChatDogRatioResponse> getChatDogRatioManual(
            @PathVariable String channelName,
            @RequestBody ManualGameSegmentRequest request) {
        
        ChatDogRatioResponse ratio = chatStatsService.calculateChatDogRatioWithSegments(channelName, request.getGameSegments());
        return ResponseEntity.ok(ratio);
    }
    
    @GetMapping("/chatdog-ratio/{channelName}/mock")
    public ResponseEntity<ChatDogRatioResponse> getMockChatDogRatio(
            @PathVariable String channelName,
            @RequestParam(defaultValue = "low") String scenario) {
        
        ChatDogRatioResponse mockRatio = chatStatsService.generateMockChatDogRatio(scenario);
        return ResponseEntity.ok(mockRatio);
    }
    
    @PostMapping("/insert-mock-data/{channelName}")
    public ResponseEntity<String> insertMockData(
            @PathVariable String channelName,
            @RequestParam(defaultValue = "medium") String scenario) {
        
        try {
            chatStatsService.insertMockDataToDatabase(channelName, scenario);
            return ResponseEntity.ok("목데이터가 데이터베이스에 성공적으로 삽입되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("목데이터 삽입 실패: " + e.getMessage());
        }
    }
    
    @GetMapping("/debug/{channelName}")
    public ResponseEntity<Object> getDebugInfo(@PathVariable String channelName) {
        return ResponseEntity.ok(chatStatsService.getDebugInfo(channelName));
    }
    
    @DeleteMapping("/clear-all-data")
    public ResponseEntity<String> clearAllData() {
        try {
            chatStatsService.clearAllData();
            return ResponseEntity.ok("모든 데이터가 삭제되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("데이터 삭제 실패: " + e.getMessage());
        }
    }
}
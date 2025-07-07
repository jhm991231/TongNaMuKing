package com.tongnamuking.tongnamuking_backend.controller;

import com.tongnamuking.tongnamuking_backend.dto.ChatStatsResponse;
import com.tongnamuking.tongnamuking_backend.dto.ChatDogRatioResponse;
import com.tongnamuking.tongnamuking_backend.dto.ManualGameSegmentRequest;
import com.tongnamuking.tongnamuking_backend.service.ChatStatsService;
import com.tongnamuking.tongnamuking_backend.service.MemoryChatDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat-stats")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000"}, allowCredentials = "true")
@Tag(name = "채팅 통계", description = "채팅 통계 및 분석 API")
@lombok.extern.slf4j.Slf4j
public class ChatStatsController {
    
    private final ChatStatsService chatStatsService;
    private final MemoryChatDataService memoryChatDataService;
    
    @GetMapping("/channel/{channelName}")
    @Operation(summary = "채널별 채팅 통계 조회", description = "지정된 채널의 채팅 통계를 조회합니다. 시간 범위를 지정할 수 있습니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "통계 조회 성공"),
        @ApiResponse(responseCode = "404", description = "채널을 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<List<ChatStatsResponse>> getChatStatsByChannel(
            @Parameter(description = "채널명", required = true) @PathVariable String channelName,
            @Parameter(description = "조회할 시간 범위 (시간 단위, 0이면 전체)") @RequestParam(defaultValue = "0") double hours) {
        
        List<ChatStatsResponse> stats;
        if (hours > 0) {
            stats = chatStatsService.getChatStatsByChannelAndTimeRange(channelName, hours);
        } else {
            stats = chatStatsService.getChatStatsByChannel(channelName);
        }
        
        return ResponseEntity.ok(stats);
    }
    
    @GetMapping("/client/channel/{channelName}")
    @Operation(summary = "클라이언트별 채널 채팅 통계 조회", description = "지정된 클라이언트의 채널별 채팅 통계를 조회합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "통계 조회 성공"),
        @ApiResponse(responseCode = "404", description = "채널을 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<List<ChatStatsResponse>> getChatStatsByChannelAndClient(
            @Parameter(description = "채널명", required = true) @PathVariable String channelName,
            @Parameter(description = "조회할 시간 범위 (시간 단위, 0이면 전체)") @RequestParam(defaultValue = "0") double hours,
            HttpServletRequest request) {
        
        log.info("채팅 통계 조회: 채널={}", channelName);
        
        List<ChatStatsResponse> stats;
        if (hours > 0) {
            stats = memoryChatDataService.getChatStatsByChannelAndTimeRange(request, channelName, hours);
        } else {
            stats = memoryChatDataService.getChatStatsByChannel(request, channelName);
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
    
    @GetMapping("/chatdog-ratio/{channelName}/auto")
    public ResponseEntity<ChatDogRatioResponse> getChatDogRatioAuto(
            @PathVariable String channelName) {
        
        ChatDogRatioResponse ratio = chatStatsService.calculateChatDogRatioAuto(channelName);
        return ResponseEntity.ok(ratio);
    }
    
    @PostMapping("/chatdog-ratio/{channelName}/manual")
    public ResponseEntity<ChatDogRatioResponse> getChatDogRatioManual(
            @PathVariable String channelName,
            @RequestBody ManualGameSegmentRequest request) {
        
        ChatDogRatioResponse ratio = chatStatsService.calculateChatDogRatioWithSegments(channelName, request.getGameSegments());
        return ResponseEntity.ok(ratio);
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
    
    @GetMapping("/memory-stats")
    @Operation(summary = "메모리 통계 조회", description = "현재 메모리에 저장된 채팅 데이터 통계를 조회합니다.")
    public ResponseEntity<Map<String, Object>> getMemoryStats() {
        return ResponseEntity.ok(memoryChatDataService.getMemoryStats());
    }
}
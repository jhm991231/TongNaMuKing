package com.tongnamuking.tongnamuking_backend.controller;

import com.tongnamuking.tongnamuking_backend.service.MultiChannelCollectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/multi-channel-collection")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "멀티채널 수집", description = "여러 채널의 채팅 수집 관리 API (독케익 제외)")
public class MultiChannelController {
    
    private final MultiChannelCollectionService multiChannelCollectionService;
    
    @PostMapping("/start/{channelId}")
    @Operation(summary = "채널 채팅 수집 시작", description = "지정된 채널의 실시간 채팅 수집을 시작합니다. (독케익 채널 제외)")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "요청 성공 (수집 시작 성공/실패 여부는 response body 확인)"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<Map<String, Object>> startCollection(
        @Parameter(description = "수집할 채널 ID", required = true) @PathVariable String channelId) {
        boolean success = multiChannelCollectionService.startCollection(channelId);
        
        String message;
        if (success) {
            message = "멀티채널 수집이 시작되었습니다";
        } else if (multiChannelCollectionService.isCollecting(channelId)) {
            message = "이미 해당 채널의 채팅을 수집 중입니다";
        } else if (multiChannelCollectionService.getActiveCollectorCount() >= multiChannelCollectionService.getMaxCollectors()) {
            message = String.format("최대 수집기 수(%d)에 도달했습니다", multiChannelCollectionService.getMaxCollectors());
        } else {
            message = "멀티채널 수집 시작에 실패했습니다";
        }
        
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", message,
            "status", multiChannelCollectionService.getStatus(),
            "activeChannels", multiChannelCollectionService.getActiveChannels(),
            "activeCount", multiChannelCollectionService.getActiveCollectorCount(),
            "maxCount", multiChannelCollectionService.getMaxCollectors()
        ));
    }
    
    @PostMapping("/stop/{channelId}")
    @Operation(summary = "채널 채팅 수집 중지", description = "지정된 채널의 실시간 채팅 수집을 중지합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "요청 성공 (수집 중지 성공/실패 여부는 response body 확인)"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<Map<String, Object>> stopCollection(
        @Parameter(description = "중지할 채널 ID", required = true) @PathVariable String channelId) {
        boolean success = multiChannelCollectionService.stopCollection(channelId);
        
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", success ? "멀티채널 수집이 중지되었습니다" : "해당 채널은 수집 중이 아닙니다",
            "status", multiChannelCollectionService.getStatus(),
            "activeChannels", multiChannelCollectionService.getActiveChannels(),
            "activeCount", multiChannelCollectionService.getActiveCollectorCount()
        ));
    }
    
    @PostMapping("/stop-all")
    @Operation(summary = "모든 채널 수집 중지", description = "현재 수집 중인 모든 채널의 채팅 수집을 중지합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "요청 성공 (단, 일부 수집 중지에 실패할 수 있음)"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<Map<String, Object>> stopAllCollections() {
        boolean success = multiChannelCollectionService.stopAllCollections();
        
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", success ? "모든 멀티채널 수집이 중지되었습니다" : "일부 수집 중지에 실패했습니다",
            "status", multiChannelCollectionService.getStatus()
        ));
    }
    
    @GetMapping("/status")
    @Operation(summary = "전체 수집 상태 조회", description = "전체 채널 수집 상태와 활성 채널 목록을 조회합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "상태 조회 성공"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
            "isAnyCollecting", multiChannelCollectionService.isAnyCollecting(),
            "activeChannels", multiChannelCollectionService.getActiveChannels(),
            "activeCount", multiChannelCollectionService.getActiveCollectorCount(),
            "maxCount", multiChannelCollectionService.getMaxCollectors(),
            "status", multiChannelCollectionService.getStatus()
        ));
    }
    
    @GetMapping("/status/{channelId}")
    @Operation(summary = "채널별 수집 상태 조회", description = "지정된 채널의 수집 상태를 조회합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "상태 조회 성공"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<Map<String, Object>> getChannelStatus(
        @Parameter(description = "조회할 채널 ID", required = true) @PathVariable String channelId) {
        return ResponseEntity.ok(Map.of(
            "channelId", channelId,
            "isCollecting", multiChannelCollectionService.isCollecting(channelId)
        ));
    }
}
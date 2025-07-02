package com.tongnamuking.tongnamuking_backend.controller;

import com.tongnamuking.tongnamuking_backend.dto.ChatMessageRequest;
import com.tongnamuking.tongnamuking_backend.service.DogCakeCollectionService;
import com.tongnamuking.tongnamuking_backend.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/dogcake-collection")
@RequiredArgsConstructor
@Tag(name = "독케익 전용 수집", description = "독케익 채널 전용 채팅 수집 관리 API")
public class DogCakeController {
    
    private final DogCakeCollectionService dogCakeCollectionService;
    private final ChatService chatService;
    
    @PostMapping("/start")
    @Operation(summary = "독케익 채팅 수집 시작", description = "독케익 채널의 실시간 채팅 수집을 시작합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "요청 성공 (수집 시작 성공/실패 여부는 response body 확인)"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<Map<String, Object>> startDogCakeCollection() {
        boolean success = dogCakeCollectionService.startDogCakeCollection();
        
        String message = success ? 
            "독케익 채팅 수집이 시작되었습니다" : 
            "독케익 채팅 수집 시작에 실패했습니다 (이미 실행 중이거나 오류 발생)";
        
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", message,
            "status", dogCakeCollectionService.getStatus(),
            "isCollecting", dogCakeCollectionService.isDogCakeCollecting(),
            "channelId", dogCakeCollectionService.getDogCakeChannelId()
        ));
    }
    
    @PostMapping("/stop")
    @Operation(summary = "독케익 채팅 수집 중지", description = "독케익 채널의 실시간 채팅 수집을 중지합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "요청 성공 (수집 중지 성공/실패 여부는 response body 확인)"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<Map<String, Object>> stopDogCakeCollection() {
        boolean success = dogCakeCollectionService.stopDogCakeCollection();
        
        String message = success ? 
            "독케익 채팅 수집이 중지되었습니다" : 
            "독케익 채팅 수집이 실행 중이 아닙니다";
        
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", message,
            "status", dogCakeCollectionService.getStatus(),
            "isCollecting", dogCakeCollectionService.isDogCakeCollecting()
        ));
    }
    
    @GetMapping("/status")
    @Operation(summary = "독케익 수집 상태 조회", description = "독케익 채널의 현재 채팅 수집 상태를 조회합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "상태 조회 성공"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<Map<String, Object>> getDogCakeStatus() {
        return ResponseEntity.ok(Map.of(
            "isCollecting", dogCakeCollectionService.isDogCakeCollecting(),
            "status", dogCakeCollectionService.getStatus(),
            "channelId", dogCakeCollectionService.getDogCakeChannelId()
        ));
    }
    
    @PostMapping("/message")
    @Operation(summary = "독케익 채팅 메시지 수신", description = "독케익 chat-collector로부터 채팅 메시지를 수신하여 데이터베이스에 저장합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "메시지 저장 성공"),
        @ApiResponse(responseCode = "500", description = "메시지 저장 실패")
    })
    public ResponseEntity<String> addDogCakeMessage(@RequestBody ChatMessageRequest request) {
        try {
            String channelName = request.getChannelName() != null ? 
                request.getChannelName() : request.getChannelId();
            
            System.out.println("독케익 채팅 수신 - 채널: " + channelName + 
                             ", 사용자: " + request.getUsername() + 
                             ", 메시지: " + request.getMessage() +
                             ", 세션: " + request.getSessionId());
            
            // 독케익 채팅을 데이터베이스에 저장 (영구 보관)
            chatService.addChatMessage(
                request.getUsername(),
                channelName,
                request.getMessage(),
                request.getSessionId()
            );
            
            System.out.println("✅ 독케익 채팅 데이터베이스에 저장 완료");
            return ResponseEntity.ok("DogCake chat message stored in database");
            
        } catch (Exception e) {
            System.err.println("❌ 독케익 채팅 메시지 저장 실패: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Failed to store DogCake chat message");
        }
    }
}
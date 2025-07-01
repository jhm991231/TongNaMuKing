package com.tongnamuking.tongnamuking_backend.controller;

import com.tongnamuking.tongnamuking_backend.service.DogCakeCollectionService;
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
@CrossOrigin(origins = "*")
@Tag(name = "독케익 전용 수집", description = "독케익 채널 전용 채팅 수집 관리 API")
public class DogCakeController {
    
    private final DogCakeCollectionService dogCakeCollectionService;
    
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
}
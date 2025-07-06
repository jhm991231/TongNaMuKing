package com.tongnamuking.tongnamuking_backend.controller;

import com.tongnamuking.tongnamuking_backend.dto.ChatMessageRequest;
import com.tongnamuking.tongnamuking_backend.service.MemoryChatDataService;
import com.tongnamuking.tongnamuking_backend.service.MultiChannelCollectionService;
import com.tongnamuking.tongnamuking_backend.service.ClientIdentifierService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {
    
    private final MemoryChatDataService memoryChatDataService;
    private final MultiChannelCollectionService multiChannelCollectionService;
    private final ClientIdentifierService clientIdentifierService;
    
    @PostMapping("/message/from-collector")
    public ResponseEntity<String> addMultiChannelMessage(@RequestBody ChatMessageRequest request) {
        try {
            // channelName이 있으면 사용하고, 없으면 channelId 사용
            String channelName = request.getChannelName() != null ? 
                request.getChannelName() : request.getChannelId();
            
            System.out.println("멀티채널 채팅 수신 - 채널: " + channelName + 
                             ", 사용자: " + request.getUsername() + 
                             ", 메시지: " + request.getMessage() +
                             ", 클라이언트: " + request.getClientId());
            
            // 멀티채널 채팅을 메모리에 저장 (임시 보관)
            memoryChatDataService.addChatMessage(
                request.getClientId(),
                request.getUsername(),
                channelName,
                request.getMessage()
            );
            
            System.out.println("✅ 멀티채널 채팅 메모리에 저장 완료");
            return ResponseEntity.ok("Multi-channel chat message stored in memory");
            
        } catch (Exception e) {
            System.err.println("❌ 멀티채널 채팅 메시지 저장 실패: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Failed to store multi-channel chat message");
        }
    }
    
    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping(HttpServletRequest request) {
        // 클라이언트 활동 시간 업데이트
        memoryChatDataService.updateClientActivity(request);
        
        String clientId = clientIdentifierService.resolveClientId(request);
        multiChannelCollectionService.updateClientActivity(clientId);
        
        return ResponseEntity.ok(Map.of(
            "status", "alive",
            "clientId", clientId,
            "timestamp", String.valueOf(System.currentTimeMillis())
        ));
    }
}
package com.tongnamuking.tongnamuking_backend.controller;

import com.tongnamuking.tongnamuking_backend.dto.ChatMessageRequest;
import com.tongnamuking.tongnamuking_backend.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatController {
    
    private final ChatService chatService;
    
    @PostMapping("/message")
    public ResponseEntity<String> addChatMessage(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String displayName = request.get("displayName");
        String channelName = request.get("channelName");
        String message = request.get("message");
        
        if (username == null || channelName == null || message == null) {
            return ResponseEntity.badRequest().body("Missing required fields");
        }
        
        if (displayName == null) {
            displayName = username;
        }
        
        chatService.addChatMessage(username, displayName, channelName, message);
        return ResponseEntity.ok("Chat message added successfully");
    }
    
    @PostMapping("/message/from-collector")
    public ResponseEntity<String> addChatMessageFromCollector(@RequestBody ChatMessageRequest request) {
        try {
            // channelName이 있으면 사용하고, 없으면 channelId 사용
            String channelName = request.getChannelName() != null ? 
                request.getChannelName() : request.getChannelId();
            
            System.out.println("채팅 수신 - 채널: " + channelName + 
                             ", 사용자: " + request.getUsername() + 
                             ", 메시지: " + request.getMessage());
            
            // 실제 채팅 메시지 저장
            chatService.addChatMessage(
                request.getUsername(),
                request.getDisplayName(),
                channelName,
                request.getMessage()
            );
            
            System.out.println("✅ 데이터베이스에 저장 완료");
            
            return ResponseEntity.ok("Chat message received and stored");
        } catch (Exception e) {
            System.err.println("❌ 채팅 메시지 저장 실패: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Failed to store chat message");
        }
    }
}
package com.tongnamuking.tongnamuking_backend.dto;

import lombok.Data;

@Data
public class ChatMessageRequest {
    private String type;          // "chat" 또는 "donation"
    private String channelId;
    private String channelName;   // 실제 채널명
    private String sessionId;     // 세션 ID
    private String userId;
    private String username;
    private String message;
    private String timestamp;
    private Boolean hidden;
    private Integer payAmount;    // 후원 금액 (후원 메시지인 경우)
}
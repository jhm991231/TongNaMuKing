package com.tongnamuking.tongnamuking_backend.websocket;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class ChzzkChatClient extends WebSocketClient {
    
    private String channelId;
    private boolean isConnected = false;
    
    public ChzzkChatClient() {
        super(URI.create("wss://kr-ss1.chat.naver.com/chat"));
    }
    
    public void connectToChannel(String channelId) {
        this.channelId = channelId;
        System.out.println("Attempting to connect to channel: " + channelId);
        
        try {
            this.connect();
        } catch (Exception e) {
            System.out.println("Failed to connect: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    @Override
    public void onOpen(ServerHandshake handshake) {
        isConnected = true;
        System.out.println("WebSocket connection opened for channel: " + channelId);
        System.out.println("Status: " + handshake.getHttpStatus());
        
        // 치지직 채팅방 연결 메시지 전송 (추후 정확한 프로토콜 확인 필요)
        String connectMessage = String.format(
            "{\"cmd\":100,\"tid\":1,\"cid\":\"%s\",\"svcid\":\"game\",\"ver\":\"2\"}", 
            channelId
        );
        
        System.out.println("Sending connect message: " + connectMessage);
        this.send(connectMessage);
    }
    
    @Override
    public void onMessage(String message) {
        System.out.println("Received message: " + message);
        
        // TODO: 메시지 파싱 및 데이터베이스 저장 로직 추가
        // 현재는 콘솔에만 출력
    }
    
    @Override
    public void onClose(int code, String reason, boolean remote) {
        isConnected = false;
        System.out.println("WebSocket connection closed");
        System.out.println("Code: " + code + ", Reason: " + reason + ", Remote: " + remote);
    }
    
    @Override
    public void onError(Exception ex) {
        System.out.println("WebSocket error: " + ex.getMessage());
        ex.printStackTrace();
    }
    
    public boolean isConnected() {
        return isConnected && this.isOpen();
    }
    
    public void disconnectFromChannel() {
        if (isConnected) {
            System.out.println("Disconnecting from channel: " + channelId);
            this.close();
        }
    }
}
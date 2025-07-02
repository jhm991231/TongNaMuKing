package com.tongnamuking.tongnamuking_backend.service;

import com.tongnamuking.tongnamuking_backend.websocket.ChzzkChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ChatCollectionService {
    
    @Autowired
    private ChzzkChatClient chatClient;
    
    private String currentChannelId;
    private boolean isCollecting = false;
    
    public boolean startCollection(String channelId) {
        if (isCollecting) {
            System.out.println("Already collecting from channel: " + currentChannelId);
            return false;
        }
        
        try {
            // 새로운 WebSocket 클라이언트 인스턴스 생성 (기존 연결이 있을 수 있으므로)
            chatClient = new ChzzkChatClient();
            chatClient.connectToChannel(channelId);
            
            currentChannelId = channelId;
            isCollecting = true;
            
            System.out.println("Started chat collection for channel: " + channelId);
            return true;
        } catch (Exception e) {
            System.out.println("Failed to start collection: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    public boolean stopCollection() {
        if (!isCollecting) {
            System.out.println("Not currently collecting");
            return false;
        }
        
        try {
            chatClient.disconnectFromChannel();
            isCollecting = false;
            currentChannelId = null;
            
            System.out.println("Stopped chat collection");
            return true;
        } catch (Exception e) {
            System.out.println("Failed to stop collection: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    public String getCollectionStatus() {
        if (isCollecting) {
            return "Collecting from channel: " + currentChannelId + 
                   " (Connected: " + (chatClient != null && chatClient.isConnected()) + ")";
        } else {
            return "Not collecting";
        }
    }
    
    public boolean isCollecting() {
        return isCollecting;
    }
    
    public String getCurrentChannelId() {
        return currentChannelId;
    }
}
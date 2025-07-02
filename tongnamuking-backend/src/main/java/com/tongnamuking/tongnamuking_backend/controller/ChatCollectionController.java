package com.tongnamuking.tongnamuking_backend.controller;

import com.tongnamuking.tongnamuking_backend.service.ChatCollectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chat-collection")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000"}, allowCredentials = "true")
public class ChatCollectionController {
    
    @Autowired
    private ChatCollectionService chatCollectionService;
    
    @PostMapping("/start/{channelId}")
    public Map<String, Object> startCollection(@PathVariable String channelId) {
        boolean success = chatCollectionService.startCollection(channelId);
        
        return Map.of(
            "success", success,
            "message", success ? "Started collecting from " + channelId : "Failed to start collection",
            "status", chatCollectionService.getCollectionStatus()
        );
    }
    
    @PostMapping("/stop")
    public Map<String, Object> stopCollection() {
        boolean success = chatCollectionService.stopCollection();
        
        return Map.of(
            "success", success,
            "message", success ? "Stopped collection" : "Failed to stop collection",
            "status", chatCollectionService.getCollectionStatus()
        );
    }
    
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        String currentChannelId = chatCollectionService.getCurrentChannelId();
        return Map.of(
            "isCollecting", chatCollectionService.isCollecting(),
            "currentChannelId", currentChannelId != null ? currentChannelId : "",
            "status", chatCollectionService.getCollectionStatus()
        );
    }
}
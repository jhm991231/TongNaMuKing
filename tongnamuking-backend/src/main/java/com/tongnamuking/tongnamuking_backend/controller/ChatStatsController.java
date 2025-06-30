package com.tongnamuking.tongnamuking_backend.controller;

import com.tongnamuking.tongnamuking_backend.dto.ChatStatsResponse;
import com.tongnamuking.tongnamuking_backend.service.ChatStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat-stats")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatStatsController {
    
    private final ChatStatsService chatStatsService;
    
    @GetMapping("/channel/{channelName}")
    public ResponseEntity<List<ChatStatsResponse>> getChatStatsByChannel(
            @PathVariable String channelName,
            @RequestParam(defaultValue = "0") int hours) {
        
        List<ChatStatsResponse> stats;
        if (hours > 0) {
            stats = chatStatsService.getChatStatsByChannelAndTimeRange(channelName, hours);
        } else {
            stats = chatStatsService.getChatStatsByChannel(channelName);
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
}
package com.tongnamuking.tongnamuking_backend.service;

import com.tongnamuking.tongnamuking_backend.dto.ChatStatsResponse;
import com.tongnamuking.tongnamuking_backend.entity.Channel;
import com.tongnamuking.tongnamuking_backend.repository.ChannelRepository;
import com.tongnamuking.tongnamuking_backend.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ChatStatsService {
    
    private final ChatMessageRepository chatMessageRepository;
    private final ChannelRepository channelRepository;
    
    public List<ChatStatsResponse> getChatStatsByChannel(String channelName) {
        Optional<Channel> channel = channelRepository.findByChannelName(channelName);
        if (channel.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<Object[]> results = chatMessageRepository.findChatStatsByChannel(channel.get().getId());
        return convertToResponseList(results);
    }
    
    public List<ChatStatsResponse> getChatStatsByChannelAndTimeRange(String channelName, int hours) {
        Optional<Channel> channel = channelRepository.findByChannelName(channelName);
        if (channel.isEmpty()) {
            return new ArrayList<>();
        }
        
        LocalDateTime startTime = LocalDateTime.now().minusHours(hours);
        List<Object[]> results = chatMessageRepository.findChatStatsByChannelAndTimeRange(
            channel.get().getId(), startTime);
        return convertToResponseList(results);
    }
    
    private List<ChatStatsResponse> convertToResponseList(List<Object[]> results) {
        List<ChatStatsResponse> responses = new ArrayList<>();
        int rank = 1;
        
        for (Object[] result : results) {
            Long userId = (Long) result[0];
            String username = (String) result[1];
            String displayName = (String) result[2];
            Long messageCount = (Long) result[3];
            
            responses.add(new ChatStatsResponse(userId, username, displayName, messageCount, rank));
            rank++;
        }
        
        return responses;
    }
}
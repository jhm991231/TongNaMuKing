package com.tongnamuking.tongnamuking_backend.service;

import com.tongnamuking.tongnamuking_backend.entity.Channel;
import com.tongnamuking.tongnamuking_backend.entity.ChatMessage;
import com.tongnamuking.tongnamuking_backend.entity.User;
import com.tongnamuking.tongnamuking_backend.repository.ChannelRepository;
import com.tongnamuking.tongnamuking_backend.repository.ChatMessageRepository;
import com.tongnamuking.tongnamuking_backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ChatService {
    
    private final UserRepository userRepository;
    private final ChannelRepository channelRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRankingService chatRankingService;

    @Transactional
    public void addChatMessage(String username, String channelName, String message, String clientId) {
        
        User user = userRepository.findByUsername(username)
            .orElseGet(() -> userRepository.save(
                User.builder()
                    .username(username)
                    .build()));

        Channel channel = channelRepository.findByChannelName(channelName)
            .orElseGet(() -> channelRepository.save(
                Channel.builder()
                    .channelName(channelName)
                    // 독케익 채널인 경우 Chzzk 채널 ID 설정
                    .chzzkChannelId("독케익".equals(channelName)
                            ? "b68af124ae2f1743a1dcbf5e2ab41e0b" : null)
                    .build()));

        ChatMessage chatMessage = ChatMessage.builder()
            .user(user)
            .channel(channel)
            .message(message)
            .clientId(clientId)
            .timestamp(LocalDateTime.now())
            .build();
        chatMessageRepository.save(chatMessage);
        
        // Redis 순위 캐시 갱신 (캐시가 있을 때만 +1, 없으면 다음 백필이 포함)
        chatRankingService.incrementDbRankingIfCached(channelName, username);
    }
}
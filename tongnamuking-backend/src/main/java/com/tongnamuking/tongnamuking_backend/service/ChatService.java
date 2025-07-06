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
    
    @Transactional
    public void addChatMessage(String username, String channelName, String message, String clientId) {
        
        User user = userRepository.findByUsername(username)
            .orElseGet(() -> {
                User newUser = new User();
                newUser.setUsername(username);
                newUser.setTotalChatCount(0);
                return userRepository.save(newUser);
            });
        
        Channel channel = channelRepository.findByChannelName(channelName)
            .orElseGet(() -> {
                Channel newChannel = new Channel();
                newChannel.setChannelName(channelName);
                
                // 독케익 채널인 경우 Chzzk 채널 ID 설정
                if ("독케익".equals(channelName)) {
                    newChannel.setChzzkChannelId("b68af124ae2f1743a1dcbf5e2ab41e0b");
                }
                
                return channelRepository.save(newChannel);
            });
        
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setUser(user);
        chatMessage.setChannel(channel);
        chatMessage.setMessage(message);
        chatMessage.setClientId(clientId);
        chatMessage.setTimestamp(LocalDateTime.now());
        chatMessageRepository.save(chatMessage);
        
        user.setTotalChatCount(user.getTotalChatCount() + 1);
        userRepository.save(user);
    }
}
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

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ChatService {
    
    private final UserRepository userRepository;
    private final ChannelRepository channelRepository;
    private final ChatMessageRepository chatMessageRepository;
    
    @Transactional
    public void addChatMessage(String username, String displayName, String channelName, String message) {
        
        User user = userRepository.findByUsername(username)
            .orElseGet(() -> {
                User newUser = new User();
                newUser.setUsername(username);
                newUser.setDisplayName(displayName);
                newUser.setTotalChatCount(0);
                return userRepository.save(newUser);
            });
        
        Channel channel = channelRepository.findByChannelName(channelName)
            .orElseGet(() -> {
                Channel newChannel = new Channel();
                newChannel.setChannelName(channelName);
                newChannel.setDisplayName(channelName);
                return channelRepository.save(newChannel);
            });
        
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setUser(user);
        chatMessage.setChannel(channel);
        chatMessage.setMessage(message);
        chatMessageRepository.save(chatMessage);
        
        user.setTotalChatCount(user.getTotalChatCount() + 1);
        userRepository.save(user);
    }
}
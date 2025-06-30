package com.tongnamuking.tongnamuking_backend.repository;

import com.tongnamuking.tongnamuking_backend.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    
    @Query("SELECT cm.user.id, cm.user.username, cm.user.displayName, COUNT(cm.id) as messageCount " +
           "FROM ChatMessage cm WHERE cm.channel.id = :channelId " +
           "GROUP BY cm.user.id, cm.user.username, cm.user.displayName " +
           "ORDER BY COUNT(cm.id) DESC")
    List<Object[]> findChatStatsByChannel(@Param("channelId") Long channelId);
    
    @Query("SELECT cm.user.id, cm.user.username, cm.user.displayName, COUNT(cm.id) as messageCount " +
           "FROM ChatMessage cm WHERE cm.channel.id = :channelId AND cm.timestamp >= :startTime " +
           "GROUP BY cm.user.id, cm.user.username, cm.user.displayName " +
           "ORDER BY COUNT(cm.id) DESC")
    List<Object[]> findChatStatsByChannelAndTimeRange(@Param("channelId") Long channelId, @Param("startTime") LocalDateTime startTime);
    
    Long countByChannelIdAndUserId(Long channelId, Long userId);
}
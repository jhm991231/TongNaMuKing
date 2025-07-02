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
    
    @Query("SELECT cm.user.id, cm.user.username, COUNT(cm.id) as messageCount " +
           "FROM ChatMessage cm WHERE cm.channel.id = :channelId " +
           "GROUP BY cm.user.id, cm.user.username " +
           "ORDER BY COUNT(cm.id) DESC")
    List<Object[]> findChatStatsByChannel(@Param("channelId") Long channelId);
    
    @Query("SELECT cm.user.id, cm.user.username, COUNT(cm.id) as messageCount " +
           "FROM ChatMessage cm WHERE cm.channel.id = :channelId AND cm.timestamp >= :startTime " +
           "GROUP BY cm.user.id, cm.user.username " +
           "ORDER BY COUNT(cm.id) DESC")
    List<Object[]> findChatStatsByChannelAndTimeRange(@Param("channelId") Long channelId, @Param("startTime") LocalDateTime startTime);
    
    @Query("SELECT cm.user.id, cm.user.username, COUNT(cm.id) as messageCount " +
           "FROM ChatMessage cm WHERE cm.channel.id = :channelId AND cm.sessionId = :sessionId " +
           "GROUP BY cm.user.id, cm.user.username " +
           "ORDER BY COUNT(cm.id) DESC")
    List<Object[]> findChatStatsByChannelAndSession(@Param("channelId") Long channelId, @Param("sessionId") String sessionId);
    
    @Query("SELECT cm.user.id, cm.user.username, COUNT(cm.id) as messageCount " +
           "FROM ChatMessage cm WHERE cm.channel.id = :channelId AND cm.sessionId = :sessionId AND cm.timestamp >= :startTime " +
           "GROUP BY cm.user.id, cm.user.username " +
           "ORDER BY COUNT(cm.id) DESC")
    List<Object[]> findChatStatsByChannelSessionAndTimeRange(@Param("channelId") Long channelId, @Param("sessionId") String sessionId, @Param("startTime") LocalDateTime startTime);
    
    // 저챗견 비율 계산을 위한 쿼리들
    @Query("SELECT DISTINCT cm.user.id " +
           "FROM ChatMessage cm WHERE cm.channel.id = :channelId " +
           "AND cm.timestamp >= :startTime AND cm.timestamp <= :endTime")
    List<Long> findDistinctUsersByChannelAndTimeRange(@Param("channelId") Long channelId, 
                                                     @Param("startTime") LocalDateTime startTime, 
                                                     @Param("endTime") LocalDateTime endTime);
    
    @Query("SELECT DISTINCT cm.user.id " +
           "FROM ChatMessage cm WHERE cm.channel.id = :channelId " +
           "AND cm.timestamp >= :gameStartTime")
    List<Long> findDistinctUsersAfterGameStart(@Param("channelId") Long channelId, 
                                              @Param("gameStartTime") LocalDateTime gameStartTime);
    
    Long countByChannelIdAndUserId(Long channelId, Long userId);
    
    // 목데이터 삽입을 위한 삭제 메서드
    void deleteByChannel(com.tongnamuking.tongnamuking_backend.entity.Channel channel);
    
    // 가장 오래된 메시지 시간 조회 (방송 시작 시간 추정용)
    @Query("SELECT MIN(cm.timestamp) FROM ChatMessage cm WHERE cm.channel.id = :channelId " +
           "AND cm.timestamp >= :startTime")
    LocalDateTime findOldestMessageTimeByChannel(@Param("channelId") Long channelId, 
                                                @Param("startTime") LocalDateTime startTime);
    
    // 세션별 채팅 메시지 삭제
    void deleteBySessionId(String sessionId);
}
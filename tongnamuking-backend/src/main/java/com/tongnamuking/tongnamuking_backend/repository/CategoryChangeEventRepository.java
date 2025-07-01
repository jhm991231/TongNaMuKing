package com.tongnamuking.tongnamuking_backend.repository;

import com.tongnamuking.tongnamuking_backend.entity.CategoryChangeEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CategoryChangeEventRepository extends JpaRepository<CategoryChangeEvent, Long> {
    
    @Query("SELECT c FROM CategoryChangeEvent c WHERE c.channel.id = :channelId " +
           "AND c.changeDetectedAt >= :startTime " +
           "ORDER BY c.changeDetectedAt ASC")
    List<CategoryChangeEvent> findByChannelAndTimeRange(@Param("channelId") Long channelId, 
                                                       @Param("startTime") LocalDateTime startTime);
    
    @Query("SELECT c FROM CategoryChangeEvent c WHERE c.channel.id = :channelId " +
           "AND c.changeDetectedAt >= :startTime AND c.changeDetectedAt <= :endTime " +
           "ORDER BY c.changeDetectedAt ASC")
    List<CategoryChangeEvent> findByChannelAndTimeRange(@Param("channelId") Long channelId,
                                                       @Param("startTime") LocalDateTime startTime,
                                                       @Param("endTime") LocalDateTime endTime);
    
    // 목데이터 삽입을 위한 삭제 메서드
    void deleteByChannel(com.tongnamuking.tongnamuking_backend.entity.Channel channel);
}
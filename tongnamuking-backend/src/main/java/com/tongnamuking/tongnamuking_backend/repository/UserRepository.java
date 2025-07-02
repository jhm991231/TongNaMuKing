package com.tongnamuking.tongnamuking_backend.repository;

import com.tongnamuking.tongnamuking_backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    
    @Query("SELECT u FROM User u ORDER BY u.totalChatCount DESC")
    List<User> findAllOrderByTotalChatCountDesc();
    
    @Query("SELECT u FROM User u JOIN ChatMessage cm ON u.id = cm.user.id WHERE cm.channel.id = :channelId GROUP BY u.id ORDER BY COUNT(cm.id) DESC")
    List<User> findTopUsersByChannelOrderByMessageCount(@Param("channelId") Long channelId);
}
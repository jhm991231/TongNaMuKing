package com.tongnamuking.tongnamuking_backend.repository;

import com.tongnamuking.tongnamuking_backend.entity.Channel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChannelRepository extends JpaRepository<Channel, Long> {
    Optional<Channel> findByChannelName(String channelName);
    List<Channel> findByChannelNameContainingIgnoreCase(String channelName);
}
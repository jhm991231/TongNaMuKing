package com.tongnamuking.tongnamuking_backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "channels")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Channel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false)
    private String channelName;
    
    private String description;
    
    // 실시간 라이브 추적 필드들
    private Boolean isCurrentlyLive = false;
    
    private LocalDateTime liveStartTime;
    
    private LocalDateTime lastLiveEndTime; // 마지막 방송 종료 시간
    
    private String chzzkChannelId; // Chzzk API 호출용 채널 ID
}
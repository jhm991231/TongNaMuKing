package com.tongnamuking.tongnamuking_backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(name = "channels")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용. 애플리케이션은 빌더를 쓴다
public class Channel {

    /** 이전 방송 종료 후 이 시간 안에 다시 켜지면 같은 방송의 연장으로 본다 */
    private static final int CONTINUOUS_BROADCAST_MINUTES = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String channelName;

    private String description;

    // 실시간 라이브 추적 필드들 — 아래 startLive/endLive 로만 바뀐다
    private Boolean isCurrentlyLive = false;

    private LocalDateTime liveStartTime;

    private LocalDateTime lastLiveEndTime; // 마지막 방송 종료 시간

    private String chzzkChannelId; // Chzzk API 호출용 채널 ID

    @Builder
    private Channel(String channelName, String description, String chzzkChannelId) {
        this.channelName = channelName;
        this.description = description;
        this.chzzkChannelId = chzzkChannelId;
    }

    /** 예전 데이터에는 isCurrentlyLive 가 null 인 행이 있어 그대로 쓰면 NPE 가 난다 */
    public boolean isLive() {
        return Boolean.TRUE.equals(isCurrentlyLive);
    }

    public void startLive(LocalDateTime now) {
        this.isCurrentlyLive = true;
        if (!isContinuationOf(now)) {
            this.liveStartTime = now; // 새 방송이므로 기준 시각을 새로 잡는다
        }
        // 연속 방송이면 liveStartTime 을 건드리지 않는다
    }

    public void endLive(LocalDateTime now) {
        this.isCurrentlyLive = false;
        this.lastLiveEndTime = now;
        // liveStartTime 은 "마지막 방송이 언제 시작했나" 기록으로 남긴다
    }

    private boolean isContinuationOf(LocalDateTime now) {
        if (lastLiveEndTime == null) {
            return false; // 종료 기록이 없으면 첫 방송이니 연속일 수 없다
        }
        return Duration.between(lastLiveEndTime, now).toMinutes() <= CONTINUOUS_BROADCAST_MINUTES;
    }
}

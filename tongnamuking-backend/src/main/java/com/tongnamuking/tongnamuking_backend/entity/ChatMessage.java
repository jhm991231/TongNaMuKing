package com.tongnamuking.tongnamuking_backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용. 애플리케이션은 빌더를 쓴다
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id", nullable = false)
    private Channel channel;

    @Column(nullable = false, length = 1000)
    private String message;

    @Column(name = "client_id", length = 255)
    private String clientId;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Builder
    private ChatMessage(User user, Channel channel, String message, String clientId,
                        LocalDateTime timestamp) {
        this.user = user;
        this.channel = channel;
        this.message = message;
        this.clientId = clientId;
        this.timestamp = timestamp;
    }
}

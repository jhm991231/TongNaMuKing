package com.tongnamuking.tongnamuking_backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "category_change_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용. 애플리케이션은 빌더를 쓴다
public class CategoryChangeEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id")
    private Channel channel;

    @Column(name = "previous_category_type")
    private String previousCategoryType;

    @Column(name = "previous_live_category")
    private String previousLiveCategory;

    @Column(name = "previous_live_category_value")
    private String previousLiveCategoryValue;

    @Column(name = "new_category_type")
    private String newCategoryType;

    @Column(name = "new_live_category")
    private String newLiveCategory;

    @Column(name = "new_live_category_value")
    private String newLiveCategoryValue;

    @Column(name = "change_detected_at")
    private LocalDateTime changeDetectedAt;

    @Builder
    private CategoryChangeEvent(Channel channel,
                                String previousCategoryType,
                                String previousLiveCategory,
                                String previousLiveCategoryValue,
                                String newCategoryType,
                                String newLiveCategory,
                                String newLiveCategoryValue,
                                LocalDateTime changeDetectedAt) {
        this.channel = channel;
        this.previousCategoryType = previousCategoryType;
        this.previousLiveCategory = previousLiveCategory;
        this.previousLiveCategoryValue = previousLiveCategoryValue;
        this.newCategoryType = newCategoryType;
        this.newLiveCategory = newLiveCategory;
        this.newLiveCategoryValue = newLiveCategoryValue;
        this.changeDetectedAt = changeDetectedAt;
    }

    @PrePersist
    protected void onCreate() {
        if (changeDetectedAt == null) {
            changeDetectedAt = LocalDateTime.now();
        }
    }
}

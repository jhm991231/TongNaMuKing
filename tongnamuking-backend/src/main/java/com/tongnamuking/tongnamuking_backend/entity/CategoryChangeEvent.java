package com.tongnamuking.tongnamuking_backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "category_change_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
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
    
    @PrePersist
    protected void onCreate() {
        if (changeDetectedAt == null) {
            changeDetectedAt = LocalDateTime.now();
        }
    }
}
package com.tongnamuking.tongnamuking_backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChzzkLiveResponse {
    private int code;
    private String message;
    private Content content;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Content {
        private String liveTitle;
        private String liveImageUrl;
        private String defaultThumbnailImageUrl;
        private int concurrentUserCount;
        private int accumulateCount;
        private boolean openLive;
        private String liveId;
        private String livePlaybackJson;
        private String chatChannelId;
        private String liveCategory;
        private String liveCategoryValue;
        private String chatActive;
        private String chatAvailableGroup;
        private String paidPromotion;
        private String chatAvailableCondition;
        private int minFollowerMinute;
    }
}
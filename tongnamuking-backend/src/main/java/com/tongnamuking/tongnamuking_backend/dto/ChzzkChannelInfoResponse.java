package com.tongnamuking.tongnamuking_backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChzzkChannelInfoResponse {
    private int code;
    private String message;
    private Content content;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Content {
        private String channelId;
        private String channelName;
        private String channelImageUrl;
        private boolean verifiedMark;
        private String channelType;
        private String channelDescription;
        private int followerCount;
        private boolean openLive;
        private boolean subscriptionAvailability;
    }
}
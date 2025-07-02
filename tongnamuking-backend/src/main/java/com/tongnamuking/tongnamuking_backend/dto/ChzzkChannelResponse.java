package com.tongnamuking.tongnamuking_backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChzzkChannelResponse {
    private int code;
    private String message;
    private Content content;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Content {
        private int size;
        private List<ChannelWrapper> data;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChannelWrapper {
        private ChzzkChannel channel;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChzzkChannel {
        private String channelId;
        private String channelName;
        private String channelImageUrl;
        private int followerCount;
        private boolean openLive;
    }
}
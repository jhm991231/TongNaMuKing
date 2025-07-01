package com.tongnamuking.tongnamuking_backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CategoryChangeRequest {
    private String channelId;
    private String channelName;
    private CategoryInfo previousCategory;
    private CategoryInfo newCategory;
    private String changeDetectedAt;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryInfo {
        private String categoryType;
        private String liveCategory;
        private String liveCategoryValue;
        private String timestamp;
    }
}
package com.tongnamuking.tongnamuking_backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ManualGameSegmentRequest {
    private List<GameSegment> gameSegments;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GameSegment {
        private Long id;
        private int startMinute;
        private int endMinute;
    }
}
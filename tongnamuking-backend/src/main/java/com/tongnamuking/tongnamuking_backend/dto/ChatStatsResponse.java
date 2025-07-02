package com.tongnamuking.tongnamuking_backend.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatStatsResponse {
    private Long userId;
    private String username;
    private Long messageCount;
    private Integer rank;
}
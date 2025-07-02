package com.tongnamuking.tongnamuking_backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatDogRatioResponse {
    private double ratio;
    private int justChatParticipants;
    private int gameParticipants;
    private int disappearedParticipants;
    private String analysisDescription;
}
package com.tongnamuking.tongnamuking_backend.controller;

import com.tongnamuking.tongnamuking_backend.dto.ChzzkChannelResponse;
import com.tongnamuking.tongnamuking_backend.dto.ChzzkChannelInfoResponse;
import com.tongnamuking.tongnamuking_backend.service.ChzzkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
@CrossOrigin(origins = { "http://localhost:5173", "http://localhost:3000" }, allowCredentials = "true")
@Tag(name = "채널 정보", description = "치지직 채널 검색 및 정보 조회 API")
public class ChannelController {

    private final ChzzkService chzzkService;

    @GetMapping("/search")
    @Operation(summary = "채널 검색", description = "지정된 키워드로 치지직 채널을 검색합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "검색 성공"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<List<ChzzkChannelResponse.ChzzkChannel>> searchChannels(
            @Parameter(description = "검색 키워드", required = true) @RequestParam String query) {
        List<ChzzkChannelResponse.ChzzkChannel> channels = chzzkService.searchChannels(query);
        return ResponseEntity.ok(channels);
    }

    @GetMapping("/{channelId}/info")
    @Operation(summary = "채널 정보 조회", description = "채널 ID로 상세 정보를 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "채널을 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<ChzzkChannelInfoResponse.Content> getChannelInfo(
            @Parameter(description = "채널 ID", required = true) @PathVariable String channelId) {
        ChzzkChannelInfoResponse.Content channelInfo = chzzkService.getChannelInfo(channelId);
        if (channelInfo == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(channelInfo);
    }
}
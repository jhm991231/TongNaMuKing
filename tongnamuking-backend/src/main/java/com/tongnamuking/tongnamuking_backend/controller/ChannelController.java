package com.tongnamuking.tongnamuking_backend.controller;

import com.tongnamuking.tongnamuking_backend.dto.ChzzkChannelResponse;
import com.tongnamuking.tongnamuking_backend.dto.ChzzkChannelInfoResponse;
import com.tongnamuking.tongnamuking_backend.service.ChzzkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/channels")
@CrossOrigin(origins = "*")
@Tag(name = "채널 정보", description = "치지직 채널 검색 및 정보 조회 API")
public class ChannelController {
    
    @Autowired
    private ChzzkService chzzkService;
    
    @GetMapping("/search")
    @Operation(summary = "채널 검색", description = "지정된 키워드로 치지직 채널을 검색합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "검색 성공"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public List<ChzzkChannelResponse.ChzzkChannel> searchChannels(
        @Parameter(description = "검색 키워드", required = true) @RequestParam String query) {
        return chzzkService.searchChannels(query);
    }
    
    @GetMapping("/{channelId}/info")
    @Operation(summary = "채널 정보 조회", description = "채널 ID로 상세 정보를 조회합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "404", description = "채널을 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ChzzkChannelInfoResponse.Content getChannelInfo(
        @Parameter(description = "채널 ID", required = true) @PathVariable String channelId) {
        return chzzkService.getChannelInfo(channelId);
    }
    
    @GetMapping("/{channelId}/live-detail")
    @Operation(summary = "라이브 상세 정보 조회", description = "채널의 라이브 방송 상세 정보를 조회합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "404", description = "라이브 정보를 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public String getLiveDetail(
        @Parameter(description = "채널 ID", required = true) @PathVariable String channelId) {
        return chzzkService.getLiveDetail(channelId);
    }
}
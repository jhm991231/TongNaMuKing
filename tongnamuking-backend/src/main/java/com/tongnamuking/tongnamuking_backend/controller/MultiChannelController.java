package com.tongnamuking.tongnamuking_backend.controller;

import com.tongnamuking.tongnamuking_backend.dto.ChatMessageRequest;
import com.tongnamuking.tongnamuking_backend.service.MultiChannelCollectionService;
import com.tongnamuking.tongnamuking_backend.service.ChatRankingService;
import com.tongnamuking.tongnamuking_backend.service.ClientIdentifierService;
import com.tongnamuking.tongnamuking_backend.service.ChannelSubscriptionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/multi-channel-collection")
@RequiredArgsConstructor
@Tag(name = "멀티채널 수집", description = "여러 채널의 채팅 수집 관리 API")
@Slf4j
public class MultiChannelController {

        private final MultiChannelCollectionService multiChannelCollectionService;
        private final ChatRankingService chatRankingService;
        private final ClientIdentifierService clientIdentifierService;
        private final ChannelSubscriptionRegistry channelSubscriptionRegistry;

        @PostMapping("/start/{channelId}")
        @Operation(summary = "채널 채팅 수집 시작", description = "지정된 채널의 실시간 채팅 수집을 시작합니다.")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "요청 성공 (수집 시작 성공/실패 여부는 response body 확인)"),
                        @ApiResponse(responseCode = "500", description = "서버 오류")
        })
        public ResponseEntity<Map<String, Object>> startCollection(
                        @Parameter(description = "수집할 채널 ID", required = true) @PathVariable String channelId,
                        HttpServletRequest request) {

                String clientId = clientIdentifierService.resolveClientId(request);
                log.info("=== 수집 시작 요청 ===");
                log.info("클라이언트 ID: {}", clientId);
                log.info("채널 ID: {}", channelId);
                log.info("현재 수집 중인 채널들: {}", multiChannelCollectionService.getActiveChannels(clientId));
                boolean success = multiChannelCollectionService.startCollection(clientId, channelId);

                String message;
                if (success) {
                        message = "멀티채널 수집이 시작되었습니다";
                } else if (multiChannelCollectionService.isCollecting(clientId, channelId)) {
                        message = "이미 해당 채널의 채팅을 수집 중입니다";
                } else if (multiChannelCollectionService
                                .getActiveCollectorCount(clientId) >= multiChannelCollectionService
                                                .getMaxCollectors()) {
                        message = String.format("최대 수집기 수(%d)에 도달했습니다",
                                        multiChannelCollectionService.getMaxCollectors());
                } else {
                        message = "멀티채널 수집 시작에 실패했습니다";
                }

                return ResponseEntity.ok(Map.of(
                                "success", success,
                                "message", message,
                                "status", multiChannelCollectionService.getStatus(clientId),
                                "activeChannels", multiChannelCollectionService.getActiveChannels(clientId),
                                "activeCount", multiChannelCollectionService.getActiveCollectorCount(clientId),
                                "maxCount", multiChannelCollectionService.getMaxCollectors()));
        }

        @PostMapping("/stop/{channelId}")
        @Operation(summary = "채널 채팅 수집 중지", description = "지정된 채널의 실시간 채팅 수집을 중지합니다.")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "요청 성공 (수집 중지 성공/실패 여부는 response body 확인)"),
                        @ApiResponse(responseCode = "500", description = "서버 오류")
        })
        public ResponseEntity<Map<String, Object>> stopCollection(
                        @Parameter(description = "중지할 채널 ID", required = true) @PathVariable String channelId,
                        HttpServletRequest request) {

                String clientId = clientIdentifierService.resolveClientId(request);
                log.info("=== 수집 중지 요청 ===");
                log.info("클라이언트 ID: {}", clientId);
                log.info("채널 ID: {}", channelId);
                log.info("현재 수집 중인 채널들: {}", multiChannelCollectionService.getActiveChannels(clientId));
                boolean success = multiChannelCollectionService.stopCollection(clientId, channelId);

                return ResponseEntity.ok(Map.of(
                                "success", success,
                                "message", success ? "멀티채널 수집이 중지되었습니다" : "해당 채널은 수집 중이 아닙니다",
                                "status", multiChannelCollectionService.getStatus(clientId),
                                "activeChannels", multiChannelCollectionService.getActiveChannels(clientId),
                                "activeCount", multiChannelCollectionService.getActiveCollectorCount(clientId)));
        }

        @GetMapping("/status")
        @Operation(summary = "전체 수집 상태 조회", description = "전체 채널 수집 상태와 활성 채널 목록을 조회합니다.")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "상태 조회 성공"),
                        @ApiResponse(responseCode = "500", description = "서버 오류")
        })
        public ResponseEntity<Map<String, Object>> getStatus(
                        HttpServletRequest request) {
                String clientId = clientIdentifierService.resolveClientId(request);
                log.info("수집 상태 조회: 클라이언트={}", clientId);

                return ResponseEntity.ok(Map.of(
                                "isAnyCollecting", multiChannelCollectionService.isAnyCollecting(clientId),
                                "activeChannels", multiChannelCollectionService.getActiveChannels(clientId),
                                "activeCount", multiChannelCollectionService.getActiveCollectorCount(clientId),
                                "maxCount", multiChannelCollectionService.getMaxCollectors(),
                                "status", multiChannelCollectionService.getStatus(clientId)));
        }

        // chat-collector 데몬이 호출하는 API. 데몬은 clientId를 모르므로 구독자는 여기서 찾는다.
        @PostMapping("/message/from-collector")
        @Operation(summary = "멀티채널 채팅 메시지 수신", description = "멀티채널 수집기 데몬으로부터 채팅 메시지를 수신하여 구독 중인 모든 클라이언트의 Redis 순위에 반영합니다.")
        public ResponseEntity<String> addMultiChannelMessage(@RequestBody ChatMessageRequest request) {
                try {
                        // channelName이 있으면 사용하고, 없으면 channelId 사용
                        String channelName = request.getChannelName() != null ? request.getChannelName()
                                        : request.getChannelId();

                        Set<String> subscribers = channelSubscriptionRegistry
                                        .getSubscribers(request.getChannelId());

                        if (subscribers.isEmpty()) {
                                // 해제 요청과 채팅 수신이 교차한 경우. 버리는 것이 맞다.
                                log.debug("구독자가 없는 채널의 채팅 수신, 무시: {}", channelName);
                                return ResponseEntity.ok("No subscribers");
                        }

                        log.debug("멀티채널 채팅 수신 - 채널: {}, 사용자: {}, 구독자 {}명",
                                        channelName, request.getUsername(), subscribers.size());

                        // 구독 중인 모든 클라이언트의 순위에 반영 (전체 순위 + 1분 버킷)
                        for (String clientId : subscribers) {
                                chatRankingService.incrementScore(
                                                clientId,
                                                channelName,
                                                request.getUsername());
                        }

                        return ResponseEntity.ok("Multi-channel chat message counted in Redis");

                } catch (Exception e) {
                        log.error("멀티채널 채팅 메시지 처리 실패: {}", e.getMessage(), e);
                        return ResponseEntity.internalServerError().body("Failed to process multi-channel chat message");
                }
        }

        @GetMapping("/ping")
        @Operation(summary = "세션 활동 핑", description = "클라이언트의 활동 시간을 갱신합니다.")
        public ResponseEntity<Map<String, String>> ping(HttpServletRequest request) {
                // 클라이언트 활동 시간 업데이트 (수집기 프로세스 정리용)
                String clientId = clientIdentifierService.resolveClientId(request);
                multiChannelCollectionService.updateClientActivity(clientId);

                return ResponseEntity.ok(Map.of(
                                "status", "alive",
                                "clientId", clientId,
                                "timestamp", String.valueOf(System.currentTimeMillis())));
        }
}
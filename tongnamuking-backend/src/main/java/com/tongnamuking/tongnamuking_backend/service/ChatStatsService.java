package com.tongnamuking.tongnamuking_backend.service;

import com.tongnamuking.tongnamuking_backend.dto.ChatStatsResponse;
import com.tongnamuking.tongnamuking_backend.dto.ChatDogRatioResponse;
import com.tongnamuking.tongnamuking_backend.dto.ManualGameSegmentRequest;
import com.tongnamuking.tongnamuking_backend.entity.Channel;
import com.tongnamuking.tongnamuking_backend.entity.CategoryChangeEvent;
import com.tongnamuking.tongnamuking_backend.repository.ChannelRepository;
import com.tongnamuking.tongnamuking_backend.repository.ChatMessageRepository;
import com.tongnamuking.tongnamuking_backend.repository.CategoryChangeEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import com.tongnamuking.tongnamuking_backend.repository.UserRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatStatsService {

    private final ChatMessageRepository chatMessageRepository;
    private final ChannelRepository channelRepository;
    private final CategoryChangeEventRepository categoryChangeEventRepository;
    private final UserRepository userRepository;

    public List<ChatStatsResponse> getChatStatsByChannel(String channelName) {
        Optional<Channel> channel = channelRepository.findByChannelName(channelName);
        if (channel.isEmpty()) {
            return new ArrayList<>();
        }

        List<Object[]> results = chatMessageRepository.findChatStatsByChannel(channel.get().getId());
        return convertToResponseList(results);
    }

    public List<ChatStatsResponse> getChatStatsByChannelAndTimeRange(String channelName, double hours) {
        Optional<Channel> channel = channelRepository.findByChannelName(channelName);
        if (channel.isEmpty()) {
            return new ArrayList<>();
        }

        // hours를 분으로 변환하여 더 정확한 시간 계산
        long minutes = Math.round(hours * 60);
        LocalDateTime startTime = LocalDateTime.now().minusMinutes(minutes);
        List<Object[]> results = chatMessageRepository.findChatStatsByChannelAndTimeRange(
                channel.get().getId(), startTime);
        return convertToResponseList(results);
    }

    public List<ChatStatsResponse> getChatStatsByChannelAndClient(String channelName, String clientId) {
        Optional<Channel> channel = channelRepository.findByChannelName(channelName);
        if (channel.isEmpty()) {
            return new ArrayList<>();
        }

        List<Object[]> results = chatMessageRepository.findChatStatsByChannelAndClient(channel.get().getId(), clientId);
        return convertToResponseList(results);
    }

    public List<ChatStatsResponse> getChatStatsByChannelClientAndTimeRange(String channelName, String clientId,
            double hours) {
        Optional<Channel> channel = channelRepository.findByChannelName(channelName);
        if (channel.isEmpty()) {
            return new ArrayList<>();
        }

        // hours를 분으로 변환하여 더 정확한 시간 계산
        long minutes = Math.round(hours * 60);
        LocalDateTime startTime = LocalDateTime.now().minusMinutes(minutes);
        List<Object[]> results = chatMessageRepository.findChatStatsByChannelClientAndTimeRange(
                channel.get().getId(), clientId, startTime);
        return convertToResponseList(results);
    }

    @Transactional
    public void deleteClientData(String clientId) {
        try {
            chatMessageRepository.deleteByClientId(clientId);
            log.info("클라이언트 {} 의 모든 채팅 데이터가 삭제되었습니다", clientId);
        } catch (Exception e) {
            log.error("클라이언트 {} 의 채팅 데이터 삭제 중 오류 발생", clientId, e);
            throw e;
        }
    }

    private List<ChatStatsResponse> convertToResponseList(List<Object[]> results) {
        List<ChatStatsResponse> responses = new ArrayList<>();
        int rank = 1;

        for (Object[] result : results) {
            Long userId = (Long) result[0];
            String username = (String) result[1];
            Long messageCount = (Long) result[2];

            responses.add(new ChatStatsResponse(userId, username, messageCount, rank));
            rank++;
        }

        return responses;
    }

    // 자동 모드 - 카테고리 변경 이벤트 기반으로 저챗견 비율 계산
    public ChatDogRatioResponse calculateChatDogRatioAuto(String channelName) {
        Optional<Channel> channel = channelRepository.findByChannelName(channelName);
        if (channel.isEmpty()) {
            return new ChatDogRatioResponse(0.0, 0, 0, 0, "채널을 찾을 수 없습니다.");
        }

        // 방송 시작부터의 카테고리 변경 이벤트들 조회
        LocalDateTime broadcastStart = getBroadcastStartTime(channel.get());
        List<CategoryChangeEvent> categoryChanges = categoryChangeEventRepository.findByChannelAndTimeRange(
                channel.get().getId(), broadcastStart);

        // 카테고리 변경 이벤트가 없으면 수동 방식으로 계산
        if (categoryChanges.isEmpty()) {
            return new ChatDogRatioResponse(0.0, 0, 0, 0, "카테고리 변경 이벤트가 감지되지 않았습니다.");
        }

        // 저챗→게임 전환 구간들 찾기
        List<ChatDogSegment> segments = findJustChatToGameSegments(categoryChanges);

        if (segments.isEmpty()) {
            return new ChatDogRatioResponse(0.0, 0, 0, 0,
                    "저챗→게임 전환이 감지되지 않았습니다. 카테고리 변경이 " + categoryChanges.size() + "개 있지만 저챗→게임 전환을 찾을 수 없습니다.");
        }

        // 모든 세그먼트의 저챗견 비율 계산
        int totalJustChatUsers = 0;
        int totalDisappearedUsers = 0;
        int totalGameUsers = 0;

        for (ChatDogSegment segment : segments) {
            // 저챗 구간의 사용자들
            List<Long> justChatUsers = chatMessageRepository.findDistinctUsersByChannelAndTimeRange(
                    channel.get().getId(), segment.justChatStart, segment.justChatEnd);

            // 게임 구간의 사용자들 (카테고리 변경 10분 후부터, 게임 후 저챗 시작 전까지)
            LocalDateTime gameAnalysisStart = segment.gameStart.plusMinutes(10);
            LocalDateTime gameAnalysisEnd = segment.gameEnd != null ? segment.gameEnd : LocalDateTime.now();

            List<Long> gameUsers = chatMessageRepository.findDistinctUsersByChannelAndTimeRange(
                    channel.get().getId(), gameAnalysisStart, gameAnalysisEnd);

            // 저챗견 계산
            Set<Long> justChatUserSet = new HashSet<>(justChatUsers);
            Set<Long> gameUserSet = new HashSet<>(gameUsers);
            Set<Long> disappearedUsers = new HashSet<>(justChatUserSet);
            disappearedUsers.removeAll(gameUserSet);

            totalJustChatUsers += justChatUsers.size();
            totalGameUsers += gameUsers.size();
            totalDisappearedUsers += disappearedUsers.size();
        }

        // 전체 저챗견 비율 계산
        double ratio = totalJustChatUsers > 0 ? (double) totalDisappearedUsers / totalJustChatUsers : 0.0;

        String description = String.format(
                "%d개의 저챗→게임 전환에서 평균 저챗견 비율 (카테고리 변경 10분 후 기준)",
                segments.size());

        return new ChatDogRatioResponse(ratio, totalJustChatUsers, totalGameUsers, totalDisappearedUsers, description);
    }

    // 수동 게임 구간들을 이용한 저챗견 비율 계산
    public ChatDogRatioResponse calculateChatDogRatioWithSegments(String channelName,
            List<ManualGameSegmentRequest.GameSegment> gameSegments) {
        Optional<Channel> channel = channelRepository.findByChannelName(channelName);
        if (channel.isEmpty()) {
            return new ChatDogRatioResponse(0.0, 0, 0, 0, "채널을 찾을 수 없습니다.");
        }

        if (gameSegments.isEmpty()) {
            return new ChatDogRatioResponse(0.0, 0, 0, 0, "게임 구간이 설정되지 않았습니다.");
        }

        // 게임 구간들을 시작 시간 순으로 정렬
        gameSegments.sort((a, b) -> Integer.compare(a.getStartMinute(), b.getStartMinute()));

        int totalJustChatUsers = 0;
        int totalDisappearedUsers = 0;
        int totalGameUsers = 0;

        // 방송 시작 시간 확인 (독케익은 실제 라이브 시작 시간, 다른 채널은 추정)
        LocalDateTime streamStartTime;
        if ("독케익".equals(channelName) && channel.get().getLiveStartTime() != null) {
            streamStartTime = channel.get().getLiveStartTime();
        } else {
            streamStartTime = findStreamStartTime(channel.get());
            if (streamStartTime == null) {
                return new ChatDogRatioResponse(0.0, 0, 0, 0, "방송 시작 시간을 알 수 없습니다.");
            }
        }

        // 각 게임 구간에 대해 저챗견 비율 계산
        for (ManualGameSegmentRequest.GameSegment segment : gameSegments) {
            // 방송 시작 후 업타임 기준으로 게임 시간 계산
            LocalDateTime gameStartTime = streamStartTime.plusMinutes(segment.getStartMinute());
            LocalDateTime gameEndTime = streamStartTime.plusMinutes(segment.getEndMinute());

            // 저챗 시간은 이전 게임 종료부터 현재 게임 시작까지
            LocalDateTime justChatStartTime = findJustChatStartForSegment(gameSegments, segment, streamStartTime);
            LocalDateTime justChatEndTime = gameStartTime;

            // 저챗 구간이 유효한 경우에만 계산
            if (justChatStartTime.isBefore(justChatEndTime)) {
                // 저챗 구간 참여자
                List<Long> justChatUsers = chatMessageRepository.findDistinctUsersByChannelAndTimeRange(
                        channel.get().getId(), justChatStartTime, justChatEndTime);

                // 게임 구간 참여자 (정확한 게임 시간)
                List<Long> gameUsers = chatMessageRepository.findDistinctUsersByChannelAndTimeRange(
                        channel.get().getId(), gameStartTime, gameEndTime);

                // 저챗견 계산
                Set<Long> justChatUserSet = new HashSet<>(justChatUsers);
                Set<Long> gameUserSet = new HashSet<>(gameUsers);
                Set<Long> disappearedUsers = new HashSet<>(justChatUserSet);
                disappearedUsers.removeAll(gameUserSet);

                totalJustChatUsers += justChatUsers.size();
                totalGameUsers += gameUsers.size();
                totalDisappearedUsers += disappearedUsers.size();
            }
        }

        // 전체 저챗견 비율 계산
        double ratio = totalJustChatUsers > 0 ? (double) totalDisappearedUsers / totalJustChatUsers : 0.0;

        String description = String.format(
                "%d개의 수동 설정 게임 구간에서 평균 저챗견 비율 (정확한 시간, 버퍼 없음)",
                gameSegments.size());

        return new ChatDogRatioResponse(ratio, totalJustChatUsers, totalGameUsers, totalDisappearedUsers, description);
    }

    // 게임 구간에 해당하는 저챗 시작 시간 찾기
    private LocalDateTime findJustChatStartForSegment(List<ManualGameSegmentRequest.GameSegment> allSegments,
            ManualGameSegmentRequest.GameSegment currentSegment,
            LocalDateTime streamStartTime) {
        // 현재 게임 구간보다 먼저 끝나는 게임 구간들 중 가장 늦게 끝나는 것 찾기
        int latestEndMinute = 0;

        for (ManualGameSegmentRequest.GameSegment segment : allSegments) {
            if (segment.getEndMinute() < currentSegment.getStartMinute() && segment.getEndMinute() > latestEndMinute) {
                latestEndMinute = segment.getEndMinute();
            }
        }

        // 이전 게임이 있으면 그 종료 시점부터, 없으면 방송 시작부터
        if (latestEndMinute > 0) {
            return streamStartTime.plusMinutes(latestEndMinute);
        } else {
            // 첫 번째 게임이면 방송 시작부터
            return streamStartTime;
        }
    }

    // 저챗→게임 전환 구간들 찾기
    private List<ChatDogSegment> findJustChatToGameSegments(List<CategoryChangeEvent> categoryChanges) {
        List<ChatDogSegment> segments = new ArrayList<>();

        for (int i = 0; i < categoryChanges.size(); i++) {
            CategoryChangeEvent current = categoryChanges.get(i);

            boolean isJustChat = isJustChatCategory(current.getPreviousCategoryType(),
                    current.getPreviousLiveCategory());
            boolean isGame = isGameCategory(current.getNewCategoryType());

            // 저챗(talk 또는 null)에서 게임으로 바뀌는 경우
            if (isJustChat && isGame) {

                // 저챗 시작 시간 찾기 (이전 게임→저챗 전환 또는 방송 시작)
                LocalDateTime justChatStart = findJustChatStartTime(categoryChanges, i);
                LocalDateTime justChatEnd = current.getChangeDetectedAt();
                LocalDateTime gameStart = current.getChangeDetectedAt();

                // 게임 종료 시간 찾기 (다음 게임→저챗 전환 또는 null)
                LocalDateTime gameEnd = findGameEndTime(categoryChanges, i);

                segments.add(new ChatDogSegment(justChatStart, justChatEnd, gameStart, gameEnd));
            }
        }

        return segments;
    }

    // 저챗 시작 시간 찾기
    private LocalDateTime findJustChatStartTime(List<CategoryChangeEvent> categoryChanges, int currentIndex) {
        // 현재 저챗→게임 전환 이전의 게임→저챗 전환 찾기
        for (int i = currentIndex - 1; i >= 0; i--) {
            CategoryChangeEvent event = categoryChanges.get(i);
            if (isGameCategory(event.getPreviousCategoryType()) &&
                    isJustChatCategory(event.getNewCategoryType(), event.getNewLiveCategory())) {
                return event.getChangeDetectedAt();
            }
        }

        // 이전 게임→저챗 전환이 없으면 저챗 구간을 충분히 커버하도록 설정 (3시간 전부터)
        return LocalDateTime.now().minusHours(3);
    }

    // 게임 종료 시간 찾기
    private LocalDateTime findGameEndTime(List<CategoryChangeEvent> categoryChanges, int currentIndex) {
        // 현재 저챗→게임 전환 이후의 게임→저챗 전환 찾기
        for (int i = currentIndex + 1; i < categoryChanges.size(); i++) {
            CategoryChangeEvent event = categoryChanges.get(i);
            if (isGameCategory(event.getPreviousCategoryType()) &&
                    isJustChatCategory(event.getNewCategoryType(), event.getNewLiveCategory())) {
                return event.getChangeDetectedAt();
            }
        }

        // 다음 게임→저챗 전환이 없으면 null (현재까지 게임 중이거나 방송 종료)
        return null;
    }

    // 저챗 카테고리인지 확인
    private boolean isJustChatCategory(String categoryType, String liveCategory) {
        return categoryType == null ||
                "ETC".equals(categoryType) ||
                "talk".equals(liveCategory) ||
                liveCategory == null;
    }

    // 게임 카테고리인지 확인
    private boolean isGameCategory(String categoryType) {
        return "GAME".equals(categoryType);
    }

    // 디버그 정보 조회
    public Object getDebugInfo(String channelName) {
        Optional<Channel> channelOpt = channelRepository.findByChannelName(channelName);
        if (channelOpt.isEmpty()) {
            return "채널을 찾을 수 없습니다: " + channelName;
        }

        Channel channel = channelOpt.get();
        LocalDateTime broadcastStart = getBroadcastStartTime(channel);

        // 카테고리 변경 이벤트 조회
        List<CategoryChangeEvent> categoryChanges = categoryChangeEventRepository.findByChannelAndTimeRange(
                channel.getId(), broadcastStart);

        // 채팅 메시지 수 조회
        long totalMessages = chatMessageRepository.count();
        long channelMessages = chatMessageRepository.findChatStatsByChannel(channel.getId()).size();

        return java.util.Map.of(
                "channelName", channelName,
                "totalMessages", totalMessages,
                "channelMessages", channelMessages,
                "categoryChanges", categoryChanges,
                "broadcastStart", broadcastStart,
                "now", LocalDateTime.now());
    }

    // 모든 데이터 삭제
    @Transactional
    public void clearAllData() {
        chatMessageRepository.deleteAll();
        categoryChangeEventRepository.deleteAll();
        userRepository.deleteAll();
    }

    // 방송 시작 시간 찾기 (가장 오래된 채팅 메시지 시간)
    private LocalDateTime findStreamStartTime(Channel channel) {
        LocalDateTime broadcastStart = getBroadcastStartTime(channel);
        return chatMessageRepository.findOldestMessageTimeByChannel(channel.getId(), broadcastStart);
    }

    // 방송 시작 시간 결정 (독케익은 liveStartTime, 다른 채널은 자정)
    private LocalDateTime getBroadcastStartTime(Channel channel) {
        if ("독케익".equals(channel.getChannelName()) && channel.getLiveStartTime() != null) {
            // 방송이 진행 중이면 방송 시작 시간부터, 아니면 방송 종료 후 30분까지 포함
            if (channel.getIsCurrentlyLive() != null && channel.getIsCurrentlyLive()) {
                return channel.getLiveStartTime();
            } else {
                // 방송 종료 후 30분 버퍼를 고려하여 계산
                LocalDateTime bufferEndTime = channel.getLiveStartTime()
                        .plusMinutes(getBroadcastDurationWithBuffer(channel));
                if (LocalDateTime.now().isBefore(bufferEndTime)) {
                    return channel.getLiveStartTime();
                }
            }
        }
        // 다른 채널은 기존 자정 기준 유지
        return LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
    }

    // 방송 지속 시간 + 버퍼 시간 계산 (분 단위)
    private long getBroadcastDurationWithBuffer(Channel channel) {
        if (channel.getLiveStartTime() == null) {
            return 0;
        }

        LocalDateTime endTime = LocalDateTime.now();
        if (channel.getIsCurrentlyLive() != null && !channel.getIsCurrentlyLive()) {
            // 방송이 끝났으면 30분 버퍼 추가
            long broadcastMinutes = java.time.Duration.between(channel.getLiveStartTime(), endTime).toMinutes();
            return broadcastMinutes + 30; // 30분 버퍼
        }

        // 방송 중이면 현재 시간까지
        return java.time.Duration.between(channel.getLiveStartTime(), endTime).toMinutes();
    }

    // 저챗→게임 전환 구간을 나타내는 내부 클래스
    private static class ChatDogSegment {
        final LocalDateTime justChatStart;
        final LocalDateTime justChatEnd;
        final LocalDateTime gameStart;
        final LocalDateTime gameEnd; // null이면 현재까지 게임 중

        ChatDogSegment(LocalDateTime justChatStart, LocalDateTime justChatEnd, LocalDateTime gameStart,
                LocalDateTime gameEnd) {
            this.justChatStart = justChatStart;
            this.justChatEnd = justChatEnd;
            this.gameStart = gameStart;
            this.gameEnd = gameEnd;
        }
    }
}
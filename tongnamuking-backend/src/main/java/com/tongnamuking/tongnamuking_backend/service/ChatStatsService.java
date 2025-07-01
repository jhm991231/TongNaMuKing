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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import com.tongnamuking.tongnamuking_backend.entity.ChatMessage;
import com.tongnamuking.tongnamuking_backend.entity.User;
import com.tongnamuking.tongnamuking_backend.repository.ChatMessageRepository;
import com.tongnamuking.tongnamuking_backend.repository.UserRepository;

@Service
@RequiredArgsConstructor
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
    
    public List<ChatStatsResponse> getChatStatsByChannelAndTimeRange(String channelName, int hours) {
        Optional<Channel> channel = channelRepository.findByChannelName(channelName);
        if (channel.isEmpty()) {
            return new ArrayList<>();
        }
        
        LocalDateTime startTime = LocalDateTime.now().minusHours(hours);
        List<Object[]> results = chatMessageRepository.findChatStatsByChannelAndTimeRange(
            channel.get().getId(), startTime);
        return convertToResponseList(results);
    }
    
    private List<ChatStatsResponse> convertToResponseList(List<Object[]> results) {
        List<ChatStatsResponse> responses = new ArrayList<>();
        int rank = 1;
        
        for (Object[] result : results) {
            Long userId = (Long) result[0];
            String username = (String) result[1];
            String displayName = (String) result[2];
            Long messageCount = (Long) result[3];
            
            responses.add(new ChatStatsResponse(userId, username, displayName, messageCount, rank));
            rank++;
        }
        
        return responses;
    }
    
    public ChatDogRatioResponse calculateChatDogRatio(String channelName, int justChatDuration, boolean useManualTime) {
        Optional<Channel> channel = channelRepository.findByChannelName(channelName);
        if (channel.isEmpty()) {
            return new ChatDogRatioResponse(0.0, 0, 0, 0, "채널을 찾을 수 없습니다.");
        }
        
        // 수동 시간 설정이 우선시됨
        if (useManualTime) {
            return calculateChatDogRatioManual(channel.get(), justChatDuration);
        }
        
        // 오늘 하루의 카테고리 변경 이벤트들 조회
        LocalDateTime today = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        List<CategoryChangeEvent> categoryChanges = categoryChangeEventRepository.findByChannelAndTimeRange(
            channel.get().getId(), today);
        
        // 카테고리 변경 이벤트가 없으면 수동 방식으로 계산
        if (categoryChanges.isEmpty()) {
            return new ChatDogRatioResponse(0.0, 0, 0, 0, "카테고리 변경 이벤트가 감지되지 않았습니다. 목데이터가 제대로 삽입되었는지 확인하세요.");
        }
        
        // 저챗→게임 전환 구간들 찾기
        List<ChatDogSegment> segments = findJustChatToGameSegments(categoryChanges);
        
        if (segments.isEmpty()) {
            return new ChatDogRatioResponse(0.0, 0, 0, 0, "저챗→게임 전환이 감지되지 않았습니다. 카테고리 변경이 " + categoryChanges.size() + "개 있지만 저챗→게임 전환을 찾을 수 없습니다.");
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
            segments.size()
        );
        
        return new ChatDogRatioResponse(ratio, totalJustChatUsers, totalGameUsers, totalDisappearedUsers, description);
    }
    
    // 수동 시간 설정으로 계산 (10분 버퍼 없음, 정확한 시간 기준)
    private ChatDogRatioResponse calculateChatDogRatioManual(Channel channel, int justChatDuration) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime streamStartTime = now.minusMinutes(justChatDuration * 2); // 저챗 + 게임 시간
        LocalDateTime justChatEndTime = now.minusMinutes(justChatDuration); // 저챗 종료 = 게임 시작
        LocalDateTime gameStartTime = justChatEndTime; // 10분 버퍼 없이 바로 게임 시작
        
        // 저챗 구간 참여자
        List<Long> justChatUsers = chatMessageRepository.findDistinctUsersByChannelAndTimeRange(
            channel.getId(), streamStartTime, justChatEndTime);
        
        // 게임 구간 참여자 (게임 시작부터 현재까지)
        List<Long> gameUsers = chatMessageRepository.findDistinctUsersAfterGameStart(
            channel.getId(), gameStartTime);
        
        Set<Long> justChatUserSet = new HashSet<>(justChatUsers);
        Set<Long> gameUserSet = new HashSet<>(gameUsers);
        Set<Long> disappearedUsers = new HashSet<>(justChatUserSet);
        disappearedUsers.removeAll(gameUserSet);
        
        int justChatParticipants = justChatUsers.size();
        int gameParticipants = gameUsers.size();
        int disappearedParticipants = disappearedUsers.size();
        
        double ratio = justChatParticipants > 0 ? (double) disappearedParticipants / justChatParticipants : 0.0;
        
        String description = String.format(
            "방송 시작부터 %d분간(저챗) 참여자 중 게임에서 채팅을 멈춘 비율 (수동 설정, 버퍼 없음)",
            justChatDuration
        );
        
        return new ChatDogRatioResponse(ratio, justChatParticipants, gameParticipants, disappearedParticipants, description);
    }
    
    // 수동 게임 구간들을 이용한 저챗견 비율 계산
    public ChatDogRatioResponse calculateChatDogRatioWithSegments(String channelName, List<ManualGameSegmentRequest.GameSegment> gameSegments) {
        Optional<Channel> channel = channelRepository.findByChannelName(channelName);
        if (channel.isEmpty()) {
            return new ChatDogRatioResponse(0.0, 0, 0, 0, "채널을 찾을 수 없습니다.");
        }
        
        if (gameSegments.isEmpty()) {
            return new ChatDogRatioResponse(0.0, 0, 0, 0, "게임 구간이 설정되지 않았습니다.");
        }
        
        // 게임 구간들을 시작 시간 순으로 정렬
        gameSegments.sort((a, b) -> Integer.compare(a.getStartMinute(), b.getStartMinute()));
        
        LocalDateTime now = LocalDateTime.now();
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
            LocalDateTime justChatStartTime = findJustChatStartForSegment(gameSegments, segment, now);
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
            gameSegments.size()
        );
        
        return new ChatDogRatioResponse(ratio, totalJustChatUsers, totalGameUsers, totalDisappearedUsers, description);
    }
    
    // 게임 구간에 해당하는 저챗 시작 시간 찾기
    private LocalDateTime findJustChatStartForSegment(List<ManualGameSegmentRequest.GameSegment> allSegments, 
                                                    ManualGameSegmentRequest.GameSegment currentSegment, 
                                                    LocalDateTime now) {
        // 현재 게임 구간보다 먼저 끝나는 게임 구간들 중 가장 늦게 끝나는 것 찾기
        int latestEndMinute = 0;
        
        for (ManualGameSegmentRequest.GameSegment segment : allSegments) {
            if (segment.getEndMinute() < currentSegment.getStartMinute() && segment.getEndMinute() > latestEndMinute) {
                latestEndMinute = segment.getEndMinute();
            }
        }
        
        // 이전 게임이 있으면 그 종료 시점부터, 없으면 현재 시간에서 충분히 앞서부터
        if (latestEndMinute > 0) {
            return now.minusMinutes(latestEndMinute);
        } else {
            // 첫 번째 게임이면 게임 시작 2배 시간 전부터 (충분한 저챗 시간 확보)
            return now.minusMinutes(currentSegment.getStartMinute() * 2);
        }
    }
    
    // 저챗→게임 전환 구간들 찾기
    private List<ChatDogSegment> findJustChatToGameSegments(List<CategoryChangeEvent> categoryChanges) {
        List<ChatDogSegment> segments = new ArrayList<>();
        
        for (int i = 0; i < categoryChanges.size(); i++) {
            CategoryChangeEvent current = categoryChanges.get(i);
            
            boolean isJustChat = isJustChatCategory(current.getPreviousCategoryType(), current.getPreviousLiveCategory());
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
    
    // 목데이터 생성 메서드
    public ChatDogRatioResponse generateMockChatDogRatio(String scenario) {
        switch (scenario.toLowerCase()) {
            case "low":
                return new ChatDogRatioResponse(
                    0.15, // 15% 저챗견 비율
                    120,  // 저챗 참여자 120명
                    102,  // 게임 참여자 102명
                    18,   // 사라진 사람 18명
                    "목데이터 - 낮은 저챗견 비율 시나리오 (양호한 상태)"
                );
                
            case "medium":
                return new ChatDogRatioResponse(
                    0.35, // 35% 저챗견 비율
                    150,  // 저챗 참여자 150명
                    98,   // 게임 참여자 98명
                    52,   // 사라진 사람 52명
                    "목데이터 - 중간 저챗견 비율 시나리오 (주의 필요)"
                );
                
            case "high":
                return new ChatDogRatioResponse(
                    0.65, // 65% 저챗견 비율
                    200,  // 저챗 참여자 200명
                    70,   // 게임 참여자 70명
                    130,  // 사라진 사람 130명
                    "목데이터 - 높은 저챗견 비율 시나리오 (경계 상태)"
                );
                
            case "extreme":
                return new ChatDogRatioResponse(
                    0.85, // 85% 저챗견 비율
                    180,  // 저챗 참여자 180명
                    27,   // 게임 참여자 27명
                    153,  // 사라진 사람 153명
                    "목데이터 - 극도로 높은 저챗견 비율 시나리오 (위험 상태)"
                );
                
            case "perfect":
                return new ChatDogRatioResponse(
                    0.05, // 5% 저챗견 비율
                    100,  // 저챗 참여자 100명
                    95,   // 게임 참여자 95명
                    5,    // 사라진 사람 5명
                    "목데이터 - 이상적인 시나리오 (저챗견 거의 없음)"
                );
                
            default:
                return generateMockChatDogRatio("low");
        }
    }
    
    // 데이터베이스에 목데이터 삽입
    @Transactional
    public void insertMockDataToDatabase(String channelName, String scenario) {
        Optional<Channel> channelOpt = channelRepository.findByChannelName(channelName);
        Channel channel;
        
        if (channelOpt.isEmpty()) {
            // 채널이 없으면 자동 생성
            channel = new Channel();
            channel.setChannelName(channelName);
            channel.setDisplayName(channelName);
            
            // 독케익 채널인 경우 Chzzk 채널 ID 설정
            if ("독케익".equals(channelName)) {
                channel.setChzzkChannelId("bd07973b6021d72512240c01a386d5c9");
            }
            
            channel = channelRepository.save(channel);
        } else {
            channel = channelOpt.get();
        }
        LocalDateTime now = LocalDateTime.now();
        
        // 기존 데이터 삭제 (테스트용)
        chatMessageRepository.deleteByChannel(channel);
        categoryChangeEventRepository.deleteByChannel(channel);
        
        // 시나리오별 데이터 생성
        switch (scenario.toLowerCase()) {
            case "medium":
                insertMediumScenarioData(channel, now);
                break;
            case "high":
                insertHighScenarioData(channel, now);
                break;
            case "low":
            default:
                insertLowScenarioData(channel, now);
                break;
        }
    }
    
    private void insertLowScenarioData(Channel channel, LocalDateTime now) {
        // 저챗 시간: 2시간 전부터 1시간 전까지 (120분)
        LocalDateTime justChatStart = now.minusHours(2);
        LocalDateTime justChatEnd = now.minusHours(1);
        
        // 게임 시간: 1시간 전부터 현재까지 (60분)
        LocalDateTime gameStart = now.minusHours(1);
        
        // 사용자 생성 및 저챗 참여자 120명 생성
        for (int i = 1; i <= 120; i++) {
            // 사용자 생성 또는 조회
            String username = "testuser" + i;
            String displayName = "테스트유저" + i;
            User user = userRepository.findByUsername(username)
                    .orElseGet(() -> {
                        User newUser = new User();
                        newUser.setUsername(username);
                        newUser.setDisplayName(displayName);
                        newUser.setTotalChatCount(0);
                        return userRepository.save(newUser);
                    });
            
            // 저챗 메시지 생성
            ChatMessage justChatMsg = new ChatMessage();
            justChatMsg.setChannel(channel);
            justChatMsg.setUser(user);
            justChatMsg.setMessage("저챗 메시지 " + i);
            int randomMinutes = (int)(Math.random() * 60); // 0~59분
            int randomSeconds = (int)(Math.random() * 60); // 0~59초
            justChatMsg.setTimestamp(justChatStart.plusMinutes(randomMinutes).plusSeconds(randomSeconds)); // 저챗 시간(60분) 동안 랜덤 분산
            chatMessageRepository.save(justChatMsg);
        }
        
        // 게임 참여자 102명 (ID 1~102가 게임에서도 채팅) - 10분 버퍼 고려
        LocalDateTime gameAnalysisStart = gameStart.plusMinutes(10);
        for (int i = 1; i <= 102; i++) {
            String username = "testuser" + i;
            User user = userRepository.findByUsername(username).orElseThrow();
            
            ChatMessage gameMsg = new ChatMessage();
            gameMsg.setChannel(channel);
            gameMsg.setUser(user);
            gameMsg.setMessage("게임 메시지 " + i);
            gameMsg.setTimestamp(gameAnalysisStart.plusMinutes((long)(Math.random() * 50))); // 게임 시간(50분) 동안 랜덤 분산
            chatMessageRepository.save(gameMsg);
        }
        
        // 카테고리 변경 이벤트 생성 (저챗 → 게임)
        CategoryChangeEvent categoryChange = new CategoryChangeEvent();
        categoryChange.setChannel(channel);
        categoryChange.setPreviousCategoryType("ETC");
        categoryChange.setPreviousLiveCategory("talk");
        categoryChange.setNewCategoryType("GAME");
        categoryChange.setNewLiveCategory("game");
        categoryChange.setChangeDetectedAt(justChatEnd);
        categoryChangeEventRepository.save(categoryChange);
    }
    
    private void insertMediumScenarioData(Channel channel, LocalDateTime now) {
        // 저챗 시간: 2시간 전부터 1시간 전까지
        LocalDateTime justChatStart = now.minusHours(2);
        LocalDateTime justChatEnd = now.minusHours(1);
        LocalDateTime gameStart = now.minusHours(1);
        
        // 저챗 참여자 150명
        for (int i = 1; i <= 150; i++) {
            String username = "testuser" + i;
            String displayName = "테스트유저" + i;
            User user = userRepository.findByUsername(username)
                    .orElseGet(() -> {
                        User newUser = new User();
                        newUser.setUsername(username);
                        newUser.setDisplayName(displayName);
                        newUser.setTotalChatCount(0);
                        return userRepository.save(newUser);
                    });
            
            ChatMessage justChatMsg = new ChatMessage();
            justChatMsg.setChannel(channel);
            justChatMsg.setUser(user);
            justChatMsg.setMessage("저챗 메시지 " + i);
            // 저챗 시간(60분) 동안 분, 초 단위로 랜덤하게 분산
            int randomMinutes = (int)(Math.random() * 60); // 0~59분
            int randomSeconds = (int)(Math.random() * 60); // 0~59초
            justChatMsg.setTimestamp(justChatStart.plusMinutes(randomMinutes).plusSeconds(randomSeconds));
            chatMessageRepository.save(justChatMsg);
        }
        
        // 게임 참여자 98명 (저챗견 52명) - 10분 버퍼 고려하여 게임 시작 10분 후부터 메시지 생성
        LocalDateTime gameAnalysisStart = gameStart.plusMinutes(10);
        
        for (int i = 1; i <= 98; i++) {
            String username = "testuser" + i;
            User user = userRepository.findByUsername(username).orElseThrow();
            
            ChatMessage gameMsg = new ChatMessage();
            gameMsg.setChannel(channel);
            gameMsg.setUser(user);
            gameMsg.setMessage("게임 메시지 " + i);
            // 게임 시간(50분) 동안 분, 초 단위로 랜덤하게 분산
            int randomMinutes = (int)(Math.random() * 50); // 0~49분
            int randomSeconds = (int)(Math.random() * 60); // 0~59초
            gameMsg.setTimestamp(gameAnalysisStart.plusMinutes(randomMinutes).plusSeconds(randomSeconds));
            chatMessageRepository.save(gameMsg);
        }
        
        // 카테고리 변경 이벤트
        CategoryChangeEvent categoryChange = new CategoryChangeEvent();
        categoryChange.setChannel(channel);
        categoryChange.setPreviousCategoryType("ETC");
        categoryChange.setPreviousLiveCategory("talk");
        categoryChange.setNewCategoryType("GAME");
        categoryChange.setNewLiveCategory("game");
        categoryChange.setChangeDetectedAt(justChatEnd);
        categoryChangeEventRepository.save(categoryChange);
    }
    
    private void insertHighScenarioData(Channel channel, LocalDateTime now) {
        // 저챗 시간: 2시간 전부터 1시간 전까지
        LocalDateTime justChatStart = now.minusHours(2);
        LocalDateTime justChatEnd = now.minusHours(1);
        LocalDateTime gameStart = now.minusHours(1);
        
        // 저챗 참여자 200명
        for (int i = 1; i <= 200; i++) {
            String username = "testuser" + i;
            String displayName = "테스트유저" + i;
            User user = userRepository.findByUsername(username)
                    .orElseGet(() -> {
                        User newUser = new User();
                        newUser.setUsername(username);
                        newUser.setDisplayName(displayName);
                        newUser.setTotalChatCount(0);
                        return userRepository.save(newUser);
                    });
            
            ChatMessage justChatMsg = new ChatMessage();
            justChatMsg.setChannel(channel);
            justChatMsg.setUser(user);
            justChatMsg.setMessage("저챗 메시지 " + i);
            int randomMinutes = (int)(Math.random() * 60); // 0~59분
            int randomSeconds = (int)(Math.random() * 60); // 0~59초
            justChatMsg.setTimestamp(justChatStart.plusMinutes(randomMinutes).plusSeconds(randomSeconds));
            chatMessageRepository.save(justChatMsg);
        }
        
        // 게임 참여자 70명 (저챗견 130명) - 10분 버퍼 고려
        LocalDateTime gameAnalysisStart = gameStart.plusMinutes(10);
        for (int i = 1; i <= 70; i++) {
            String username = "testuser" + i;
            User user = userRepository.findByUsername(username).orElseThrow();
            
            ChatMessage gameMsg = new ChatMessage();
            gameMsg.setChannel(channel);
            gameMsg.setUser(user);
            gameMsg.setMessage("게임 메시지 " + i);
            gameMsg.setTimestamp(gameAnalysisStart.plusMinutes((long)(Math.random() * 50))); // 게임 시간(50분) 동안 랜덤 분산
            chatMessageRepository.save(gameMsg);
        }
        
        // 카테고리 변경 이벤트
        CategoryChangeEvent categoryChange = new CategoryChangeEvent();
        categoryChange.setChannel(channel);
        categoryChange.setPreviousCategoryType("ETC");
        categoryChange.setPreviousLiveCategory("talk");
        categoryChange.setNewCategoryType("GAME");
        categoryChange.setNewLiveCategory("game");
        categoryChange.setChangeDetectedAt(justChatEnd);
        categoryChangeEventRepository.save(categoryChange);
    }
    
    // 디버그 정보 조회
    public Object getDebugInfo(String channelName) {
        Optional<Channel> channelOpt = channelRepository.findByChannelName(channelName);
        if (channelOpt.isEmpty()) {
            return "채널을 찾을 수 없습니다: " + channelName;
        }
        
        Channel channel = channelOpt.get();
        LocalDateTime today = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        
        // 카테고리 변경 이벤트 조회
        List<CategoryChangeEvent> categoryChanges = categoryChangeEventRepository.findByChannelAndTimeRange(
            channel.getId(), today);
        
        // 채팅 메시지 수 조회
        long totalMessages = chatMessageRepository.count();
        long channelMessages = chatMessageRepository.findChatStatsByChannel(channel.getId()).size();
        
        return java.util.Map.of(
            "channelName", channelName,
            "totalMessages", totalMessages,
            "channelMessages", channelMessages,
            "categoryChanges", categoryChanges,
            "todayStart", today,
            "now", LocalDateTime.now()
        );
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
        LocalDateTime today = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        return chatMessageRepository.findOldestMessageTimeByChannel(channel.getId(), today);
    }
    
    // 저챗→게임 전환 구간을 나타내는 내부 클래스
    private static class ChatDogSegment {
        final LocalDateTime justChatStart;
        final LocalDateTime justChatEnd;
        final LocalDateTime gameStart;
        final LocalDateTime gameEnd; // null이면 현재까지 게임 중
        
        ChatDogSegment(LocalDateTime justChatStart, LocalDateTime justChatEnd, LocalDateTime gameStart, LocalDateTime gameEnd) {
            this.justChatStart = justChatStart;
            this.justChatEnd = justChatEnd;
            this.gameStart = gameStart;
            this.gameEnd = gameEnd;
        }
    }
}
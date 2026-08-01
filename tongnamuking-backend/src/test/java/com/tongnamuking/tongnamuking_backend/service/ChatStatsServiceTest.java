package com.tongnamuking.tongnamuking_backend.service;

import com.tongnamuking.tongnamuking_backend.dto.ChatDogRatioResponse;
import com.tongnamuking.tongnamuking_backend.dto.ManualGameSegmentRequest.GameSegment;
import com.tongnamuking.tongnamuking_backend.entity.Channel;
import com.tongnamuking.tongnamuking_backend.repository.CategoryChangeEventRepository;
import com.tongnamuking.tongnamuking_backend.repository.ChannelRepository;
import com.tongnamuking.tongnamuking_backend.repository.ChatMessageRepository;
import com.tongnamuking.tongnamuking_backend.repository.UserRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 저챗견 비율 계산 로직 단위 테스트.
 *
 * <p>DB도 Redis도 띄우지 않는다. Repository는 전부 목(mock)으로 대체하고,
 * ChatStatsService 안의 계산 로직만 진짜로 실행한다.
 *
 * <p>목이 하는 일은 두 가지다.
 * <ul>
 *   <li>given(...).willReturn(...) — "이 상황이라면" 을 연출 (스터빙)</li>
 *   <li>verify(...)                — "제대로 불렀는가" 를 검증</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)   // @Mock / @InjectMocks 를 처리해주는 확장
@DisplayName("ChatStatsService - 저챗견 비율 계산")
class ChatStatsServiceTest {

    // 각각 런타임에 자식 클래스가 생성되어, 모든 메서드가 빈 껍데기로 오버라이드된다.
    @Mock ChatMessageRepository chatMessageRepository;
    @Mock ChannelRepository channelRepository;
    @Mock CategoryChangeEventRepository categoryChangeEventRepository;
    @Mock UserRepository userRepository;
    @Mock ChatRankingService chatRankingService;

    // 위 목들을 생성자에 끼워넣어 "진짜" ChatStatsService 를 만든다.
    // (@RequiredArgsConstructor 가 만든 5-인자 생성자를 Mockito 가 찾아 쓴다)
    @InjectMocks ChatStatsService chatStatsService;

    private static final String CHANNEL = "독케익";
    private static final Long CHANNEL_ID = 1L;

    /**
     * 방송 시작 시각을 고정한다.
     * 서비스는 채널명이 "독케익"이고 liveStartTime 이 있으면 그 값을 기준점으로 삼으므로,
     * LocalDateTime.now() 에 의존하지 않는 결정적(deterministic) 테스트가 된다.
     */
    private static final LocalDateTime STREAM_START = LocalDateTime.of(2026, 7, 29, 20, 0);

    /**
     * STREAM_START 에 시작해 지금도 방송 중인 독케익 채널.
     *
     * <p>id 는 빌더로 넣을 수 없다. DB 가 매기는 값(@GeneratedValue)이라 운영 코드에서
     * 지정하면 save() 가 INSERT 대신 UPDATE 로 동작하는 사고가 나기 때문이다.
     * 그 제약을 테스트 편의로 풀어주는 대신, 여기서만 리플렉션으로 우회한다.
     *
     * <p>방송 상태도 세터가 없다. startLive() 를 부르면 isCurrentlyLive 와 liveStartTime 이
     * 함께 맞춰지므로 운영 코드와 같은 경로로 상태를 만든다.
     */
    private Channel 독케익채널() {
        Channel channel = Channel.builder()
                .channelName(CHANNEL)
                .build();
        ReflectionTestUtils.setField(channel, "id", CHANNEL_ID);
        channel.startLive(STREAM_START);
        return channel;
    }

    /** (시작분, 종료분) 쌍을 나열하면 게임 구간 리스트를 만들어준다. */
    private List<GameSegment> 게임구간(int... 시작종료쌍) {
        List<GameSegment> list = new ArrayList<>();
        for (int i = 0; i < 시작종료쌍.length; i += 2) {
            list.add(new GameSegment((long) (i / 2 + 1), 시작종료쌍[i], 시작종료쌍[i + 1]));
        }
        return list;
    }

    /** 방송 시작 기준 n분 뒤 시각 */
    private LocalDateTime 분(int minutes) {
        return STREAM_START.plusMinutes(minutes);
    }

    // ------------------------------------------------------------------
    // 1. 기본 계산
    // ------------------------------------------------------------------

    @Test
    @DisplayName("저챗 참여자 중 게임 구간에 사라진 사람의 비율을 계산한다")
    void 저챗견_비율을_계산한다() {
        // given ─ 목이 무엇을 돌려줄지 지정한다. 실제 DB는 관여하지 않는다.
        given(channelRepository.findByChannelName(CHANNEL))
                .willReturn(Optional.of(독케익채널()));

        // 저챗 구간 20:00~21:00 → 5명 참여
        given(chatMessageRepository.findDistinctUsersByChannelAndTimeRange(
                CHANNEL_ID, 분(0), 분(60)))
                .willReturn(List.of(1L, 2L, 3L, 4L, 5L));

        // 게임 구간 21:00~22:00 → 그 중 2명만 남음
        given(chatMessageRepository.findDistinctUsersByChannelAndTimeRange(
                CHANNEL_ID, 분(60), 분(120)))
                .willReturn(List.of(1L, 2L));

        // when ─ 검증 대상 로직은 진짜로 실행된다
        ChatDogRatioResponse result =
                chatStatsService.calculateChatDogRatioWithSegments(CHANNEL, 게임구간(60, 120));

        // then ─ 3명(3,4,5)이 사라졌으므로 3/5 = 0.6
        assertThat(result.getRatio()).isCloseTo(0.6, within(0.0001));
        assertThat(result.getJustChatParticipants()).isEqualTo(5);
        assertThat(result.getGameParticipants()).isEqualTo(2);
        assertThat(result.getDisappearedParticipants()).isEqualTo(3);
        assertThat(result.getAnalysisDescription()).contains("1개의 수동 설정 게임 구간");
    }

    @Test
    @DisplayName("게임 구간이 여러 개면 각 구간의 결과를 합산한다")
    void 여러_구간을_합산한다() {
        given(channelRepository.findByChannelName(CHANNEL))
                .willReturn(Optional.of(독케익채널()));

        // 1번 구간: 저챗 20:00~20:30(4명) → 게임 20:30~21:00(2명), 사라짐 2명
        given(chatMessageRepository.findDistinctUsersByChannelAndTimeRange(CHANNEL_ID, 분(0), 분(30)))
                .willReturn(List.of(1L, 2L, 3L, 4L));
        given(chatMessageRepository.findDistinctUsersByChannelAndTimeRange(CHANNEL_ID, 분(30), 분(60)))
                .willReturn(List.of(1L, 2L));

        // 2번 구간: 저챗 21:00~21:30(4명) → 게임 21:30~22:00(1명), 사라짐 3명
        //           저챗 시작이 "이전 게임 종료 시각(60분)"으로 잡히는지가 핵심
        given(chatMessageRepository.findDistinctUsersByChannelAndTimeRange(CHANNEL_ID, 분(60), 분(90)))
                .willReturn(List.of(1L, 2L, 5L, 6L));
        given(chatMessageRepository.findDistinctUsersByChannelAndTimeRange(CHANNEL_ID, 분(90), 분(120)))
                .willReturn(List.of(1L));

        ChatDogRatioResponse result = chatStatsService
                .calculateChatDogRatioWithSegments(CHANNEL, 게임구간(30, 60, 90, 120));

        // 저챗 8명 중 5명 이탈 → 0.625
        assertThat(result.getJustChatParticipants()).isEqualTo(8);
        assertThat(result.getGameParticipants()).isEqualTo(3);
        assertThat(result.getDisappearedParticipants()).isEqualTo(5);
        assertThat(result.getRatio()).isCloseTo(0.625, within(0.0001));
    }

    @Test
    @DisplayName("게임 구간을 뒤죽박죽 넣어도 시작 시간 순으로 정렬해 계산한다")
    void 입력_순서와_무관하게_정렬된다() {
        given(channelRepository.findByChannelName(CHANNEL))
                .willReturn(Optional.of(독케익채널()));

        given(chatMessageRepository.findDistinctUsersByChannelAndTimeRange(CHANNEL_ID, 분(0), 분(30)))
                .willReturn(List.of(1L, 2L, 3L, 4L));
        given(chatMessageRepository.findDistinctUsersByChannelAndTimeRange(CHANNEL_ID, 분(30), 분(60)))
                .willReturn(List.of(1L, 2L));
        given(chatMessageRepository.findDistinctUsersByChannelAndTimeRange(CHANNEL_ID, 분(60), 분(90)))
                .willReturn(List.of(1L, 2L, 5L, 6L));
        given(chatMessageRepository.findDistinctUsersByChannelAndTimeRange(CHANNEL_ID, 분(90), 분(120)))
                .willReturn(List.of(1L));

        // 90-120 구간을 먼저 넣었지만 결과는 위 테스트와 동일해야 한다
        ChatDogRatioResponse result = chatStatsService
                .calculateChatDogRatioWithSegments(CHANNEL, 게임구간(90, 120, 30, 60));

        assertThat(result.getRatio()).isCloseTo(0.625, within(0.0001));
    }

    // ------------------------------------------------------------------
    // 2. verify — "무엇을 어떤 인자로 불렀는가"
    // ------------------------------------------------------------------

    @Test
    @DisplayName("방송 시작 시각을 기준으로 올바른 조회 구간을 만든다")
    void 조회_구간을_검증한다() {
        given(channelRepository.findByChannelName(CHANNEL))
                .willReturn(Optional.of(독케익채널()));
        given(chatMessageRepository.findDistinctUsersByChannelAndTimeRange(any(), any(), any()))
                .willReturn(List.of(1L));

        chatStatsService.calculateChatDogRatioWithSegments(CHANNEL, 게임구간(60, 120));

        // ArgumentCaptor - 목에 실제로 넘어간 인자를 붙잡아 검사한다
        ArgumentCaptor<LocalDateTime> 시작 = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> 종료 = ArgumentCaptor.forClass(LocalDateTime.class);

        verify(chatMessageRepository, times(2))
                .findDistinctUsersByChannelAndTimeRange(eq(CHANNEL_ID), 시작.capture(), 종료.capture());

        // 1번째 호출 = 저챗 구간(20:00~21:00), 2번째 호출 = 게임 구간(21:00~22:00)
        assertThat(시작.getAllValues()).containsExactly(분(0), 분(60));
        assertThat(종료.getAllValues()).containsExactly(분(60), 분(120));
    }

    // ------------------------------------------------------------------
    // 3. 예외 경로 — 실제 DB로는 만들기 번거로운 상황들
    // ------------------------------------------------------------------

    @Test
    @DisplayName("채널이 없으면 채팅 조회를 아예 시도하지 않는다")
    void 채널이_없으면_조회하지_않는다() {
        given(channelRepository.findByChannelName("없는채널"))
                .willReturn(Optional.empty());

        ChatDogRatioResponse result =
                chatStatsService.calculateChatDogRatioWithSegments("없는채널", 게임구간(60, 120));

        assertThat(result.getRatio()).isZero();
        assertThat(result.getAnalysisDescription()).isEqualTo("채널을 찾을 수 없습니다.");

        // never() - 불필요한 DB 접근이 없었음을 보장한다.
        // 반환값만 봐서는 알 수 없는 것을 검증하는 게 verify 의 존재 이유다.
        verify(chatMessageRepository, never())
                .findDistinctUsersByChannelAndTimeRange(any(), any(), any());
    }

    @Test
    @DisplayName("게임 구간이 비어 있으면 안내 메시지를 반환한다")
    void 게임_구간이_비면_계산하지_않는다() {
        given(channelRepository.findByChannelName(CHANNEL))
                .willReturn(Optional.of(독케익채널()));

        ChatDogRatioResponse result =
                chatStatsService.calculateChatDogRatioWithSegments(CHANNEL, new ArrayList<>());

        assertThat(result.getAnalysisDescription()).isEqualTo("게임 구간이 설정되지 않았습니다.");
        verify(chatMessageRepository, never())
                .findDistinctUsersByChannelAndTimeRange(any(), any(), any());
    }

    @Test
    @DisplayName("방송 시작과 동시에 게임이면 저챗 구간이 없어 비율은 0이다")
    void 저챗_구간이_없으면_0이다() {
        given(channelRepository.findByChannelName(CHANNEL))
                .willReturn(Optional.of(독케익채널()));

        // 0분부터 게임 → 저챗 시작(20:00) == 저챗 종료(20:00) → 구간이 성립하지 않음
        ChatDogRatioResponse result =
                chatStatsService.calculateChatDogRatioWithSegments(CHANNEL, 게임구간(0, 60));

        assertThat(result.getRatio()).isZero();
        assertThat(result.getJustChatParticipants()).isZero();
        verify(chatMessageRepository, never())
                .findDistinctUsersByChannelAndTimeRange(any(), any(), any());
    }

    // ------------------------------------------------------------------
    // 4. 현재 동작을 기록해두는 테스트 (잠재적 함정)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("[주의] 불변 리스트를 넘기면 내부 정렬에서 예외가 터진다")
    void 불변_리스트는_예외가_발생한다() {
        given(channelRepository.findByChannelName(CHANNEL))
                .willReturn(Optional.of(독케익채널()));

        // 서비스가 gameSegments.sort(...) 로 인자를 직접 수정하기 때문이다.
        // 지금은 Jackson 이 만든 ArrayList 가 들어오므로 문제가 없지만,
        // 호출부가 List.of(...) 로 바뀌는 순간 500 에러가 된다.
        List<GameSegment> 불변리스트 = List.of(new GameSegment(1L, 60, 120));

        assertThatThrownBy(() ->
                chatStatsService.calculateChatDogRatioWithSegments(CHANNEL, 불변리스트))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

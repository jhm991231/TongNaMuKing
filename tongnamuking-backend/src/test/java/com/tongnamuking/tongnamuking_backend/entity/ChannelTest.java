package com.tongnamuking.tongnamuking_backend.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 채널의 방송 시작/종료 상태 전이 단위 테스트.
 *
 * <p>스프링도 DB도 치지직 API도 띄우지 않는다. Channel 객체 하나만 놓고
 * "이 시각에 켜지면 / 꺼지면 어떤 상태가 되는가" 만 검증한다.
 *
 * <p>핵심 규칙: 이전 방송이 끝난 뒤 30분 이내에 다시 켜지면 같은 방송이 이어진 것으로 보고
 * liveStartTime 을 갱신하지 않는다. 저챗견 비율이 이 시각을 기준으로 계산되므로,
 * 잠깐 끊겼다 재개한 방송에서 기준점이 밀리면 안 되기 때문이다.
 *
 * <p>현재 시각을 인자로 받는 이유도 여기 있다. 메서드 안에서 LocalDateTime.now() 를 부르면
 * 30분 경계를 검증할 방법이 없어진다.
 */
@DisplayName("Channel - 방송 시작/종료 상태 전이")
class ChannelTest {

    private static final LocalDateTime STREAM_START = LocalDateTime.of(2026, 7, 31, 20, 0);

    /** 방송 이력이 없는 새 채널 */
    private Channel 채널() {
        return Channel.builder().channelName("독케익").build();
    }

    /** 방송 시작 기준 n분 뒤 시각 */
    private LocalDateTime 분(int minutes) {
        return STREAM_START.plusMinutes(minutes);
    }

    @Test
    @DisplayName("새로 만든 채널은 라이브 상태가 아니다")
    void 새_채널은_라이브가_아니다() {
        Channel channel = 채널();

        assertThat(channel.isLive()).isFalse();
    }

    @Test
    @DisplayName("처음 방송을 켜면 라이브 상태가 되고 시작 시각이 기록된다")
    void 첫_방송을_시작한다() {
        // given ─ 방송 이력이 없는 채널
        Channel channel = 채널();

        // when
        channel.startLive(STREAM_START);

        // then
        assertThat(channel.isLive()).isTrue();
        assertThat(channel.getLiveStartTime()).isEqualTo(STREAM_START);
    }

    @Test
    @DisplayName("방송을 끄면 종료 시각이 기록되고 시작 시각은 그대로 남는다")
    void 방송을_종료한다() {
        // given ─ 20:00 에 시작한 방송
        Channel channel = 채널();
        channel.startLive(STREAM_START);

        // when ─ 2시간 뒤 종료
        channel.endLive(분(120));

        // then ─ 시작 시각은 "마지막 방송이 언제 시작했나" 기록으로 남는다
        assertThat(channel.isLive()).isFalse();
        assertThat(channel.getLastLiveEndTime()).isEqualTo(분(120));
        assertThat(channel.getLiveStartTime()).isEqualTo(STREAM_START);
    }

    @Test
    @DisplayName("종료 후 30분 이내에 다시 켜지면 연속 방송이라 시작 시각을 유지한다")
    void 연속_방송은_시작_시각을_유지한다() {
        // given ─ 20:00 시작, 22:00 종료
        Channel channel = 채널();
        channel.startLive(STREAM_START);
        channel.endLive(분(120));

        // when ─ 10분 뒤 재개
        channel.startLive(분(130));

        // then ─ 같은 방송이 이어진 것으로 본다
        assertThat(channel.isLive()).isTrue();
        assertThat(channel.getLiveStartTime()).isEqualTo(STREAM_START);
    }

    @Test
    @DisplayName("종료 후 30분이 지나 다시 켜지면 새 방송이라 시작 시각을 갱신한다")
    void 새_방송은_시작_시각을_갱신한다() {
        // given ─ 20:00 시작, 22:00 종료
        Channel channel = 채널();
        channel.startLive(STREAM_START);
        channel.endLive(분(120));

        // when ─ 40분 뒤 시작
        channel.startLive(분(160));

        // then ─ 별개의 방송이다
        assertThat(channel.isLive()).isTrue();
        assertThat(channel.getLiveStartTime()).isEqualTo(분(160));
    }
}

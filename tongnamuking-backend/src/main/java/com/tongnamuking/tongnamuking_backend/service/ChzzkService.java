package com.tongnamuking.tongnamuking_backend.service;

import com.tongnamuking.tongnamuking_backend.dto.ChzzkChannelResponse;
import com.tongnamuking.tongnamuking_backend.dto.ChzzkChannelInfoResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import org.springframework.scheduling.annotation.Scheduled;
import com.tongnamuking.tongnamuking_backend.entity.Channel;
import com.tongnamuking.tongnamuking_backend.repository.ChannelRepository;

@Service
@Slf4j
public class ChzzkService {

    private final RestTemplate restTemplate;
    private final ChannelRepository channelRepository;
    private static final String CHZZK_API_BASE_URL = "https://api.chzzk.naver.com/service/v1";

    public ChzzkService(RestTemplate restTemplate, ChannelRepository channelRepository) {
        this.restTemplate = restTemplate;
        this.channelRepository = channelRepository;
    }

    public List<ChzzkChannelResponse.ChzzkChannel> searchChannels(String keyword) {
        try {
            String url = UriComponentsBuilder.fromUriString(CHZZK_API_BASE_URL + "/search/channels")
                    .queryParam("keyword", keyword)
                    .queryParam("offset", 0)
                    .queryParam("size", 10)
                    .build()
                    .toUriString();

            log.debug("Chzzk API URL: {}", url);

            ChzzkChannelResponse response = restTemplate.getForObject(url, ChzzkChannelResponse.class);
            log.debug("Parsed Response: {}", response);

            if (response != null && response.getCode() == 200 && response.getContent() != null) {
                log.debug("Found {} channel wrappers", response.getContent().getData().size());

                // ChannelWrapper에서 실제 Channel 객체 추출
                List<ChzzkChannelResponse.ChzzkChannel> channels = response.getContent().getData()
                        .stream()
                        .map(wrapper -> wrapper.getChannel())
                        .collect(java.util.stream.Collectors.toList());

                channels.forEach(channel -> {
                    log.debug("Channel: {} (ID: {})", channel.getChannelName(), channel.getChannelId());
                });

                return channels;
            }

            return List.of();
        } catch (Exception e) {
            log.error("채널 검색 실패: keyword={}", keyword, e);
            return List.of();
        }
    }

    public ChzzkChannelInfoResponse.Content getChannelInfo(String channelId) {
        try {
            String url = CHZZK_API_BASE_URL + "/channels/" + channelId;
            log.debug("Getting channel info from: {}", url);

            ChzzkChannelInfoResponse response = restTemplate.getForObject(url, ChzzkChannelInfoResponse.class);

            if (response != null && response.getCode() == 200) {
                log.debug("Channel: {}, Live: {}",
                        response.getContent().getChannelName(), response.getContent().isOpenLive());
                return response.getContent();
            }

            return null;
        } catch (Exception e) {
            log.error("채널 정보 조회 실패: channelId={}", channelId, e);
            return null;
        }
    }

    // 독케익 채널의 라이브 상태를 30초마다 체크
    @Scheduled(fixedRate = 30000)
    public void checkDogCakeLiveStatus() {
        try {
            Optional<Channel> dogCakeChannel = channelRepository.findByChannelName("독케익");
            if (dogCakeChannel.isEmpty() || dogCakeChannel.get().getChzzkChannelId() == null) {
                return; // 독케익 채널이 없거나 Chzzk 채널 ID가 설정되지 않음
            }

            Channel channel = dogCakeChannel.get();
            ChzzkChannelInfoResponse.Content channelInfo = getChannelInfo(channel.getChzzkChannelId());

            if (channelInfo != null) {
                boolean nowLive = channelInfo.isOpenLive();

                // 라이브 상태가 바뀐 순간에만 기록한다
                if (nowLive != channel.isLive()) {
                    LocalDateTime now = LocalDateTime.now();

                    if (nowLive) {
                        // 연속 방송 여부 판단과 시작 시각 갱신은 Channel 이 안다
                        channel.startLive(now);
                        log.info("독케익 라이브 시작 감지: {}", now);
                    } else {
                        channel.endLive(now);
                        log.info("독케익 라이브 종료 감지: {}", now);
                    }

                    channelRepository.save(channel);
                }
            }
        } catch (Exception e) {
            log.error("독케익 라이브 상태 체크 실패", e);
        }
    }
}

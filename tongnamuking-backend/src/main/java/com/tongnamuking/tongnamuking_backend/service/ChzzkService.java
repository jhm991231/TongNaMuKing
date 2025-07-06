package com.tongnamuking.tongnamuking_backend.service;

import com.tongnamuking.tongnamuking_backend.dto.ChzzkChannelResponse;
import com.tongnamuking.tongnamuking_backend.dto.ChzzkChannelInfoResponse;
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
            
            System.out.println("Chzzk API URL: " + url);
            
            // 원본 응답을 String으로 먼저 받아서 확인
            String rawResponse = restTemplate.getForObject(url, String.class);
            System.out.println("Raw Response: " + rawResponse);
            
            ChzzkChannelResponse response = restTemplate.getForObject(url, ChzzkChannelResponse.class);
            System.out.println("Parsed Response: " + response);
            
            if (response != null && response.getCode() == 200 && response.getContent() != null) {
                System.out.println("Found " + response.getContent().getData().size() + " channel wrappers");
                
                // ChannelWrapper에서 실제 Channel 객체 추출
                List<ChzzkChannelResponse.ChzzkChannel> channels = response.getContent().getData()
                        .stream()
                        .map(wrapper -> wrapper.getChannel())
                        .collect(java.util.stream.Collectors.toList());
                
                channels.forEach(channel -> {
                    System.out.println("Channel: " + channel.getChannelName() + " (ID: " + channel.getChannelId() + ")");
                });
                
                return channels;
            }
            
            return List.of();
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }
    
    public ChzzkChannelInfoResponse.Content getChannelInfo(String channelId) {
        try {
            String url = CHZZK_API_BASE_URL + "/channels/" + channelId;
            System.out.println("Getting channel info from: " + url);
            
            ChzzkChannelInfoResponse response = restTemplate.getForObject(url, ChzzkChannelInfoResponse.class);
            
            if (response != null && response.getCode() == 200) {
                System.out.println("Channel: " + response.getContent().getChannelName() + 
                                 ", Live: " + response.getContent().isOpenLive());
                return response.getContent();
            }
            
            return null;
        } catch (Exception e) {
            System.out.println("Error getting channel info: " + e.getMessage());
            e.printStackTrace();
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
                boolean isCurrentlyLive = channelInfo.isOpenLive();
                boolean wasLive = channel.getIsCurrentlyLive() != null ? channel.getIsCurrentlyLive() : false;
                
                // 라이브 상태가 변경된 경우
                if (isCurrentlyLive != wasLive) {
                    channel.setIsCurrentlyLive(isCurrentlyLive);
                    
                    if (isCurrentlyLive && !wasLive) {
                        // 라이브 시작
                        LocalDateTime now = LocalDateTime.now();
                        
                        // 이전 방송 종료 후 30분 이내면 연속 방송으로 처리
                        if (channel.getLastLiveEndTime() != null && 
                            java.time.Duration.between(channel.getLastLiveEndTime(), now).toMinutes() <= 30) {
                            System.out.println("독케익 연속 방송 감지 (이전 종료: " + channel.getLastLiveEndTime() + ", 현재 시작: " + now + ")");
                            // liveStartTime은 유지 (연속 방송)
                        } else {
                            // 새로운 방송 시작
                            channel.setLiveStartTime(now);
                            System.out.println("독케익 새 방송 시작 감지: " + now);
                        }
                    } else if (!isCurrentlyLive && wasLive) {
                        // 라이브 종료
                        LocalDateTime now = LocalDateTime.now();
                        channel.setLastLiveEndTime(now);
                        System.out.println("독케익 라이브 종료 감지: " + now);
                        // liveStartTime은 유지 (마지막 방송 시작 시간 기록용)
                    }
                    
                    channelRepository.save(channel);
                }
            }
        } catch (Exception e) {
            System.out.println("독케익 라이브 상태 체크 실패: " + e.getMessage());
        }
    }
}
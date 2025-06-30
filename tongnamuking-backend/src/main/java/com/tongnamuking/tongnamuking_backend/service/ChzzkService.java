package com.tongnamuking.tongnamuking_backend.service;

import com.tongnamuking.tongnamuking_backend.dto.ChzzkChannelResponse;
import com.tongnamuking.tongnamuking_backend.dto.ChzzkLiveResponse;
import com.tongnamuking.tongnamuking_backend.dto.ChzzkChannelInfoResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

@Service
public class ChzzkService {
    
    private final RestTemplate restTemplate;
    private static final String CHZZK_API_BASE_URL = "https://api.chzzk.naver.com/service/v1";
    
    public ChzzkService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }
    
    public List<ChzzkChannelResponse.ChzzkChannel> searchChannels(String keyword) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(CHZZK_API_BASE_URL + "/search/channels")
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
    
    public String getLiveDetail(String channelId) {
        // 1. 라이브 검색 API를 통해 해당 채널의 라이브 정보 찾기
        try {
            String searchUrl = CHZZK_API_BASE_URL + "/search/lives?keyword=" + channelId + "&offset=0&size=50";
            System.out.println("Searching for live streams: " + searchUrl);
            
            String searchResponse = restTemplate.getForObject(searchUrl, String.class);
            System.out.println("Live search response: " + searchResponse);
            
            return searchResponse;
        } catch (Exception e) {
            System.out.println("Live search failed: " + e.getMessage());
        }
        
        // 2. 직접 라이브 ID를 구성해서 시도
        try {
            // 일반적으로 channelId를 기반으로 liveId가 만들어지는 경우가 있음
            String directUrl = CHZZK_API_BASE_URL + "/lives/" + channelId + "/status";
            System.out.println("Trying direct live status: " + directUrl);
            
            String response = restTemplate.getForObject(directUrl, String.class);
            System.out.println("Direct live status response: " + response);
            
            return response;
        } catch (Exception e) {
            System.out.println("Direct live status failed: " + e.getMessage());
        }
        
        // 3. 채널 이름으로 라이브 검색
        ChzzkChannelInfoResponse.Content channelInfo = getChannelInfo(channelId);
        if (channelInfo != null && channelInfo.isOpenLive()) {
            try {
                String nameSearchUrl = CHZZK_API_BASE_URL + "/search/lives?keyword=" + 
                    java.net.URLEncoder.encode(channelInfo.getChannelName(), "UTF-8") + "&offset=0&size=10";
                System.out.println("Searching by channel name: " + nameSearchUrl);
                
                String response = restTemplate.getForObject(nameSearchUrl, String.class);
                System.out.println("Name search response: " + response);
                
                return response;
            } catch (Exception e) {
                System.out.println("Name search failed: " + e.getMessage());
            }
        }
        
        return null;
    }
}
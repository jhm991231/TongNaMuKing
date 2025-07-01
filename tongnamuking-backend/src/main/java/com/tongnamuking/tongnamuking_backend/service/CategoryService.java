package com.tongnamuking.tongnamuking_backend.service;

import com.tongnamuking.tongnamuking_backend.dto.CategoryChangeRequest;
import com.tongnamuking.tongnamuking_backend.entity.CategoryChangeEvent;
import com.tongnamuking.tongnamuking_backend.entity.Channel;
import com.tongnamuking.tongnamuking_backend.repository.CategoryChangeEventRepository;
import com.tongnamuking.tongnamuking_backend.repository.ChannelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CategoryService {
    
    private final CategoryChangeEventRepository categoryChangeEventRepository;
    private final ChannelRepository channelRepository;
    
    public void saveCategoryChangeEvent(CategoryChangeRequest request) {
        // 채널 ID로 채널 찾기 또는 생성
        Optional<Channel> channelOpt = channelRepository.findByChannelName(request.getChannelName());
        Channel channel;
        
        if (channelOpt.isEmpty()) {
            // 채널이 없으면 새로 생성
            channel = new Channel();
            channel.setChannelName(request.getChannelName());
            channel.setDisplayName(request.getChannelName());
            channel = channelRepository.save(channel);
        } else {
            channel = channelOpt.get();
        }
        
        // 카테고리 변경 이벤트 저장
        CategoryChangeEvent event = new CategoryChangeEvent();
        event.setChannel(channel);
        
        // 이전 카테고리 정보
        if (request.getPreviousCategory() != null) {
            event.setPreviousCategoryType(request.getPreviousCategory().getCategoryType());
            event.setPreviousLiveCategory(request.getPreviousCategory().getLiveCategory());
            event.setPreviousLiveCategoryValue(request.getPreviousCategory().getLiveCategoryValue());
        }
        
        // 새 카테고리 정보
        if (request.getNewCategory() != null) {
            event.setNewCategoryType(request.getNewCategory().getCategoryType());
            event.setNewLiveCategory(request.getNewCategory().getLiveCategory());
            event.setNewLiveCategoryValue(request.getNewCategory().getLiveCategoryValue());
        }
        
        // 변경 감지 시간 파싱
        try {
            LocalDateTime changeTime = LocalDateTime.parse(request.getChangeDetectedAt(), 
                DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            event.setChangeDetectedAt(changeTime);
        } catch (Exception e) {
            // 파싱 실패 시 현재 시간 사용
            event.setChangeDetectedAt(LocalDateTime.now());
        }
        
        categoryChangeEventRepository.save(event);
        
        System.out.println("카테고리 변경 이벤트 저장됨: " + 
            (request.getPreviousCategory() != null ? request.getPreviousCategory().getLiveCategoryValue() : "null") + 
            " → " + 
            (request.getNewCategory() != null ? request.getNewCategory().getLiveCategoryValue() : "null"));
    }
}
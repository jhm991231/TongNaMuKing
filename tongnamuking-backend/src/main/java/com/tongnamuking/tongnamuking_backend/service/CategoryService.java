package com.tongnamuking.tongnamuking_backend.service;

import com.tongnamuking.tongnamuking_backend.dto.CategoryChangeRequest;
import com.tongnamuking.tongnamuking_backend.entity.CategoryChangeEvent;
import com.tongnamuking.tongnamuking_backend.entity.Channel;
import com.tongnamuking.tongnamuking_backend.repository.CategoryChangeEventRepository;
import com.tongnamuking.tongnamuking_backend.repository.ChannelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryService {
    
    private final CategoryChangeEventRepository categoryChangeEventRepository;
    private final ChannelRepository channelRepository;
    
    public void saveCategoryChangeEvent(CategoryChangeRequest request) {
        // 채널 ID로 채널 찾기 또는 생성
        Optional<Channel> channelOpt = channelRepository.findByChannelName(request.getChannelName());
        Channel channel;
        
        if (channelOpt.isEmpty()) {
            // 채널이 없으면 새로 생성
            channel = channelRepository.save(
                Channel.builder()
                    .channelName(request.getChannelName())
                    .build());
        } else {
            channel = channelOpt.get();
        }

        // 카테고리 변경 이벤트 저장
        // 카테고리 정보가 선택적이라 조건부로 채워야 하므로, 빌더를 변수에 두고 마지막에 build 한다
        var eventBuilder = CategoryChangeEvent.builder()
            .channel(channel)
            .changeDetectedAt(parseChangeDetectedAt(request.getChangeDetectedAt()));

        // 이전 카테고리 정보
        if (request.getPreviousCategory() != null) {
            eventBuilder.previousCategoryType(request.getPreviousCategory().getCategoryType())
                        .previousLiveCategory(request.getPreviousCategory().getLiveCategory())
                        .previousLiveCategoryValue(request.getPreviousCategory().getLiveCategoryValue());
        }

        // 새 카테고리 정보
        if (request.getNewCategory() != null) {
            eventBuilder.newCategoryType(request.getNewCategory().getCategoryType())
                        .newLiveCategory(request.getNewCategory().getLiveCategory())
                        .newLiveCategoryValue(request.getNewCategory().getLiveCategoryValue());
        }

        categoryChangeEventRepository.save(eventBuilder.build());
        
        log.info("카테고리 변경 이벤트 저장됨: {} → {}",
                request.getPreviousCategory() != null ? request.getPreviousCategory().getLiveCategoryValue() : "null",
                request.getNewCategory() != null ? request.getNewCategory().getLiveCategoryValue() : "null");
    }

    /** 파싱에 실패하면 현재 시각으로 대신한다 (기존 동작 유지) */
    private LocalDateTime parseChangeDetectedAt(String raw) {
        try {
            return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}
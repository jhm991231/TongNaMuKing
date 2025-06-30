package com.tongnamuking.tongnamuking_backend.controller;

import com.tongnamuking.tongnamuking_backend.dto.ChzzkChannelResponse;
import com.tongnamuking.tongnamuking_backend.dto.ChzzkChannelInfoResponse;
import com.tongnamuking.tongnamuking_backend.service.ChzzkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/channels")
@CrossOrigin(origins = "*")
public class ChannelController {
    
    @Autowired
    private ChzzkService chzzkService;
    
    @GetMapping("/search")
    public List<ChzzkChannelResponse.ChzzkChannel> searchChannels(@RequestParam String query) {
        return chzzkService.searchChannels(query);
    }
    
    @GetMapping("/{channelId}/info")
    public ChzzkChannelInfoResponse.Content getChannelInfo(@PathVariable String channelId) {
        return chzzkService.getChannelInfo(channelId);
    }
    
    @GetMapping("/{channelId}/live-detail")
    public String getLiveDetail(@PathVariable String channelId) {
        return chzzkService.getLiveDetail(channelId);
    }
}
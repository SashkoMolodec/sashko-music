package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.events.PlaceReleaseTaskEvent;
import com.sashkomusic.libraryagent.domain.service.ReleasePlacementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class PlaceReleaseListener {

    private final ReleasePlacementService placementService;

    @EventListener
    @Async("asyncExecutor")
    public void handle(PlaceReleaseTaskEvent event) {
        log.info("Received place release task: conversationId={}, releaseId={}", event.conversationId(), event.releaseId());
        placementService.confirm(event.conversationId(), event.releaseId());
    }
}

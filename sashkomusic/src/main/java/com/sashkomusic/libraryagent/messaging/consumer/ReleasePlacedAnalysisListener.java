package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.events.ReleasePlacedEvent;
import com.sashkomusic.libraryagent.domain.service.ReleaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReleasePlacedAnalysisListener {

    private final ReleaseService releaseService;

    @EventListener
    @Async("asyncExecutor")
    public void handle(ReleasePlacedEvent event) {
        log.info("Release {} placed — requesting audio analysis from {}", event.releaseId(), event.directoryPath());
        releaseService.requestAnalysis(event.releaseId());
    }
}

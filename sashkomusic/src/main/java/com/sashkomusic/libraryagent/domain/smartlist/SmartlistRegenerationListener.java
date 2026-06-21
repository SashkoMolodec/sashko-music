package com.sashkomusic.libraryagent.domain.smartlist;

import com.sashkomusic.events.LibraryProcessingCompleteEvent;
import com.sashkomusic.events.ReprocessReleaseCompleteEvent;
import com.sashkomusic.events.SmartlistsRegeneratedEvent;
import com.sashkomusic.events.TagChangesNotificationEvent;
import com.sashkomusic.events.TrackAnalysisCompleteEvent;
import com.sashkomusic.events.TrackUpdateResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmartlistRegenerationListener {

    private final SmartlistService smartlistService;
    private final ApplicationEventPublisher eventPublisher;

    @Async("asyncExecutor")
    @EventListener
    public void onTrackUpdate(TrackUpdateResultEvent event) {
        regenerate();
    }

    @Async("asyncExecutor")
    @EventListener
    public void onTagChanges(TagChangesNotificationEvent event) {
        regenerate();
    }

    @Async("asyncExecutor")
    @EventListener
    public void onTrackAnalysisComplete(TrackAnalysisCompleteEvent event) {
        regenerate();
    }

    @Async("asyncExecutor")
    @EventListener
    public void onLibraryProcessingComplete(LibraryProcessingCompleteEvent event) {
        regenerate();
    }

    @Async("asyncExecutor")
    @EventListener
    public void onReprocessComplete(ReprocessReleaseCompleteEvent event) {
        regenerate();
    }

    private void regenerate() {
        try {
            SmartlistService.RegenerationResult result = smartlistService.regenerateAll();
            if (result.total() > 0) {
                eventPublisher.publishEvent(new SmartlistsRegeneratedEvent(result.count(), result.total()));
            }
        } catch (Exception e) {
            log.warn("Smartlist regeneration failed: {}", e.getMessage());
        }
    }
}

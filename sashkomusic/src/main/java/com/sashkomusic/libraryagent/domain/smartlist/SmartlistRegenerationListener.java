package com.sashkomusic.libraryagent.domain.smartlist;

import com.sashkomusic.events.LibraryProcessingCompleteEvent;
import com.sashkomusic.events.TagChangesNotificationEvent;
import com.sashkomusic.events.TrackAnalysisCompleteEvent;
import com.sashkomusic.events.TrackUpdateResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmartlistRegenerationListener {

    private final SmartlistService smartlistService;

    @Async("asyncExecutor")
    @EventListener
    public void onTrackUpdate(TrackUpdateResultEvent event) {
        regenerate("TrackUpdateResultEvent");
    }

    @Async("asyncExecutor")
    @EventListener
    public void onTagChanges(TagChangesNotificationEvent event) {
        regenerate("TagChangesNotificationEvent");
    }

    @Async("asyncExecutor")
    @EventListener
    public void onTrackAnalysisComplete(TrackAnalysisCompleteEvent event) {
        regenerate("TrackAnalysisCompleteEvent");
    }

    @Async("asyncExecutor")
    @EventListener
    public void onLibraryProcessingComplete(LibraryProcessingCompleteEvent event) {
        regenerate("LibraryProcessingCompleteEvent");
    }

    private void regenerate(String trigger) {
        try {
            smartlistService.regenerateAll();
        } catch (Exception e) {
            log.warn("Smartlist regeneration after {} failed: {}", trigger, e.getMessage());
        }
    }
}

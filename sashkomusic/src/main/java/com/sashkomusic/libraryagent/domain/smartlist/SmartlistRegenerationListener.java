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
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmartlistRegenerationListener {

    private static final long DEBOUNCE_MS = 3 * 60 * 1_000L;

    private final SmartlistService smartlistService;
    private final ApplicationEventPublisher eventPublisher;
    private final TaskScheduler taskScheduler;

    private final AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();

    @Async("asyncExecutor")
    @EventListener
    public void onTrackUpdate(TrackUpdateResultEvent event) {
        scheduleDebounced();
    }

    @EventListener
    @Async("asyncExecutor")
    public void onTagChanges(TagChangesNotificationEvent event) {
        regenerateNow();
    }

    @EventListener
    @Async("asyncExecutor")
    public void onTrackAnalysisComplete(TrackAnalysisCompleteEvent event) {
        regenerateNow();
    }

    @EventListener
    @Async("asyncExecutor")
    public void onLibraryProcessingComplete(LibraryProcessingCompleteEvent event) {
        regenerateNow();
    }

    @EventListener
    @Async("asyncExecutor")
    public void onReprocessComplete(ReprocessReleaseCompleteEvent event) {
        regenerateNow();
    }

    private void scheduleDebounced() {
        ScheduledFuture<?> prev = pending.getAndSet(
                taskScheduler.schedule(this::regenerateNow, Instant.now().plusMillis(DEBOUNCE_MS))
        );
        if (prev != null) {
            prev.cancel(false);
        }
        log.debug("Smartlist regeneration debounced — will run in {} ms", DEBOUNCE_MS);
    }

    private void regenerateNow() {
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

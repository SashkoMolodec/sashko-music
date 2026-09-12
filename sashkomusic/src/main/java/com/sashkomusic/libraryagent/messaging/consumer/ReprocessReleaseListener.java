package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.events.ReprocessReleaseTaskEvent;
import com.sashkomusic.libraryagent.domain.service.processFolder.ReprocessingService;
import com.sashkomusic.events.ReprocessReleaseCompleteEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReprocessReleaseListener {

    private final ReprocessingService reprocessingService;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    @Async("asyncExecutor")
    public void handleReprocessTask(ReprocessReleaseTaskEvent event) {
        log.info("Received reprocess task: conversationId={}, directoryPath={}, version={}, options={}",
                event.conversationId(), event.directoryPath(), event.newMetadataVersion(), event.options());

        try {
            ReprocessingService.ReprocessResult result = reprocessingService.reprocess(
                    event.directoryPath(), event.metadata(), event.newMetadataVersion(), event.options()
            );

            eventPublisher.publishEvent(new ReprocessReleaseCompleteEvent(
                    event.conversationId(), event.directoryPath(), result.success(), result.message(),
                    result.filesProcessed(), result.errors()));
            log.info("Reprocessing completed: success={}, filesProcessed={}, errors={}",
                    result.success(), result.filesProcessed(), result.errors());

        } catch (Exception ex) {
            log.error("Fatal error during reprocessing: {}", ex.getMessage(), ex);
            eventPublisher.publishEvent(new ReprocessReleaseCompleteEvent(
                    event.conversationId(), event.directoryPath(), false, "Fatal error: " + ex.getMessage(), 0, 1));
        }
    }
}

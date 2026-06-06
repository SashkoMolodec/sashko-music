package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.events.RemoveReleaseCompleteEvent;
import com.sashkomusic.events.RemoveReleaseTaskEvent;
import com.sashkomusic.libraryagent.domain.service.ReleaseRemovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class RemoveReleaseListener {

    private final ReleaseRemovalService removalService;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    @Async
    public void handle(RemoveReleaseTaskEvent event) {
        log.info("Received remove release task: conversationId={}, releaseId={}",
                event.conversationId(), event.releaseId());

        try {
            ReleaseRemovalService.RemovalResult result = removalService.remove(event.releaseId());
            eventPublisher.publishEvent(new RemoveReleaseCompleteEvent(
                    event.conversationId(),
                    event.releaseId(),
                    result.releaseTitle(),
                    result.directoryPath(),
                    result.trashPath(),
                    result.success(),
                    result.message()
            ));
        } catch (Exception ex) {
            log.error("Fatal error during release removal: {}", ex.getMessage(), ex);
            eventPublisher.publishEvent(new RemoveReleaseCompleteEvent(
                    event.conversationId(),
                    event.releaseId(),
                    null,
                    null,
                    null,
                    false,
                    "fatal error: " + ex.getMessage()
            ));
        }
    }
}

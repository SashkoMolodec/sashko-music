package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.events.MoveReleaseCompleteEvent;
import com.sashkomusic.events.MoveReleaseTaskEvent;
import com.sashkomusic.libraryagent.domain.service.ReleaseRelocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class MoveReleaseListener {

    private final ReleaseRelocationService relocationService;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    @Async
    public void handle(MoveReleaseTaskEvent event) {
        log.info("Received move release task: conversationId={}, releaseId={}, target={}",
                event.conversationId(), event.releaseId(), event.targetSublibrary());

        try {
            ReleaseRelocationService.RelocationResult result =
                    relocationService.move(event.releaseId(), event.targetSublibrary());

            eventPublisher.publishEvent(new MoveReleaseCompleteEvent(
                    event.conversationId(),
                    event.releaseId(),
                    result.title(),
                    result.artistName(),
                    result.oldPath(),
                    result.newPath(),
                    event.targetSublibrary(),
                    result.success(),
                    result.message()
            ));
        } catch (Exception ex) {
            log.error("Fatal error during release move: {}", ex.getMessage(), ex);
            eventPublisher.publishEvent(new MoveReleaseCompleteEvent(
                    event.conversationId(), event.releaseId(),
                    null, null, null, null, event.targetSublibrary(),
                    false, "fatal error: " + ex.getMessage()
            ));
        }
    }
}

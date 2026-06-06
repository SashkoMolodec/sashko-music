package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.MoveReleaseTaskEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class MoveReleaseTaskProducer {

    private final ApplicationEventPublisher eventPublisher;

    public void send(String conversationId, Long releaseId, String targetSublibrary) {
        log.info("Sending move release task: conversationId={}, releaseId={}, target={}",
                conversationId, releaseId, targetSublibrary);
        eventPublisher.publishEvent(new MoveReleaseTaskEvent(conversationId, releaseId, targetSublibrary));
    }
}

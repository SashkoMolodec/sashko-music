package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.RemoveReleaseTaskEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class RemoveReleaseTaskProducer {

    private final ApplicationEventPublisher eventPublisher;

    public void send(String conversationId, Long releaseId) {
        log.info("Sending remove release task: conversationId={}, releaseId={}", conversationId, releaseId);
        eventPublisher.publishEvent(new RemoveReleaseTaskEvent(conversationId, releaseId));
    }
}

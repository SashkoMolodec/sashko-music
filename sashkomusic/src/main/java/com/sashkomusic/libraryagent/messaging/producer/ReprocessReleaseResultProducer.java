package com.sashkomusic.libraryagent.messaging.producer;

import com.sashkomusic.events.ReprocessReleaseCompleteEvent;
import com.sashkomusic.libraryagent.messaging.producer.dto.ReprocessReleaseResultDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReprocessReleaseResultProducer {

    private final ApplicationEventPublisher eventPublisher;

    public void send(ReprocessReleaseResultDto message) {
        log.info("Sending reprocess result: chatId={}, success={}, filesProcessed={}", message.chatId(), message.success(), message.filesProcessed());
        eventPublisher.publishEvent(new ReprocessReleaseCompleteEvent(message));
    }
}

package com.sashkomusic.libraryagent.messaging.producer;

import com.sashkomusic.events.LibraryProcessingCompleteEvent;
import com.sashkomusic.libraryagent.messaging.producer.dto.LibraryProcessingCompleteDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class LibraryProcessingResultProducer {

    private final ApplicationEventPublisher eventPublisher;

    public void send(LibraryProcessingCompleteDto message) {
        log.info("Sending library processing result: chatId={}, success={}", message.chatId(), message.success());
        eventPublisher.publishEvent(new LibraryProcessingCompleteEvent(message));
    }
}

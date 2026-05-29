package com.sashkomusic.downloadagent.messaging.producer;

import com.sashkomusic.downloadagent.messaging.producer.dto.DownloadErrorDto;
import com.sashkomusic.events.DownloadErrorEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class DownloadErrorProducer {

    private final ApplicationEventPublisher eventPublisher;

    public void sendError(DownloadErrorDto error) {
        log.info("Sending download error for chatId={}: {}", error.chatId(), error.errorMessage());
        eventPublisher.publishEvent(new DownloadErrorEvent(error));
    }
}

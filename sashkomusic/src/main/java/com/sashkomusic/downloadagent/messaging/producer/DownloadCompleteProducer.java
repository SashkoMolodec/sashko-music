package com.sashkomusic.downloadagent.messaging.producer;

import com.sashkomusic.downloadagent.messaging.producer.dto.DownloadCompleteDto;
import com.sashkomusic.events.DownloadCompleteEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class DownloadCompleteProducer {

    private final ApplicationEventPublisher eventPublisher;

    public void sendComplete(DownloadCompleteDto complete) {
        log.info("Sending download complete: {} - {} MB", complete.filename(), complete.sizeMB());
        eventPublisher.publishEvent(new DownloadCompleteEvent(complete));
    }
}

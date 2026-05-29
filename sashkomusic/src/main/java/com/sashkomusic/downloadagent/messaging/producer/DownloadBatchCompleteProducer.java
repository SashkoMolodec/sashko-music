package com.sashkomusic.downloadagent.messaging.producer;

import com.sashkomusic.downloadagent.messaging.producer.dto.DownloadBatchCompleteDto;
import com.sashkomusic.events.DownloadBatchCompleteEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class DownloadBatchCompleteProducer {

    private final ApplicationEventPublisher eventPublisher;

    public void sendBatchComplete(DownloadBatchCompleteDto dto) {
        log.info("Sending download batch complete: releaseId={}, chatId={}, files={}", dto.releaseId(), dto.chatId(), dto.totalFiles());
        eventPublisher.publishEvent(new DownloadBatchCompleteEvent(dto));
    }
}

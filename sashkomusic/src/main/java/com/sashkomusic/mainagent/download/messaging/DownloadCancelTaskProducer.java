package com.sashkomusic.mainagent.download.messaging;

import com.sashkomusic.events.DownloadCancelTaskEvent;
import com.sashkomusic.mainagent.download.messaging.dto.DownloadCancelTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class DownloadCancelTaskProducer {

    private final ApplicationEventPublisher eventPublisher;

    public void send(DownloadCancelTaskDto task) {
        log.info("Sending cancel download task: chatId={}, releaseId={}", task.chatId(), task.releaseId());
        eventPublisher.publishEvent(new DownloadCancelTaskEvent(task));
    }
}

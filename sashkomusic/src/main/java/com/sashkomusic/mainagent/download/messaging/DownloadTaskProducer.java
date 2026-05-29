package com.sashkomusic.mainagent.download.messaging;

import com.sashkomusic.events.FilesDownloadTaskEvent;
import com.sashkomusic.mainagent.download.messaging.dto.DownloadFilesTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class DownloadTaskProducer {

    private final ApplicationEventPublisher eventPublisher;

    public void send(DownloadFilesTaskDto task) {
        log.info("Sending task to download release files: {}", task);
        eventPublisher.publishEvent(new FilesDownloadTaskEvent(task));
    }
}

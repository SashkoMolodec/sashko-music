package com.sashkomusic.mainagent.download.messaging;

import com.sashkomusic.events.FilesSearchTaskEvent;
import com.sashkomusic.mainagent.download.messaging.dto.SearchFilesTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class SearchFilesTaskProducer {

    private final ApplicationEventPublisher eventPublisher;

    public void send(SearchFilesTaskDto task) {
        log.info("Sending task to search release files: {}", task);
        eventPublisher.publishEvent(new FilesSearchTaskEvent(task));
    }
}

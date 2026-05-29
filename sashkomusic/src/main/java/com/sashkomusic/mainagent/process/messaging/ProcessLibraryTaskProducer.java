package com.sashkomusic.mainagent.process.messaging;

import com.sashkomusic.events.ProcessLibraryTaskEvent;
import com.sashkomusic.mainagent.process.messaging.dto.ProcessLibraryTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ProcessLibraryTaskProducer {

    private final ApplicationEventPublisher eventPublisher;

    public void send(ProcessLibraryTaskDto dto) {
        log.info("Sending library processing task: masterId={}, files={}", dto.metadata().masterId(), dto.downloadedFiles().size());
        eventPublisher.publishEvent(new ProcessLibraryTaskEvent(dto));
    }
}

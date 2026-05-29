package com.sashkomusic.mainagent.process.messaging;

import com.sashkomusic.events.ReprocessReleaseTaskEvent;
import com.sashkomusic.mainagent.process.messaging.dto.ReprocessReleaseTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReprocessReleaseTaskProducer {

    private final ApplicationEventPublisher eventPublisher;

    public void send(ReprocessReleaseTaskDto dto) {
        log.info("Sending reprocess task: directory={}, source={}", dto.directoryPath(), dto.metadata().source());
        eventPublisher.publishEvent(new ReprocessReleaseTaskEvent(dto));
    }
}

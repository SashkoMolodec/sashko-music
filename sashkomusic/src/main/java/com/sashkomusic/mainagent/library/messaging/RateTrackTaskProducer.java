package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.RateTrackTaskEvent;
import com.sashkomusic.mainagent.library.messaging.dto.RateTrackTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class RateTrackTaskProducer {

    private final ApplicationEventPublisher eventPublisher;

    public void send(RateTrackTaskDto task) {
        log.info("Sending rate track task: trackId={}, rating={}, chatId={}", task.trackId(), task.rating(), task.chatId());
        eventPublisher.publishEvent(new RateTrackTaskEvent(task));
    }
}

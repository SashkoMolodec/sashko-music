package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.SetEnergyTaskEvent;
import com.sashkomusic.mainagent.library.messaging.dto.SetEnergyTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class SetEnergyTaskProducer {

    private final ApplicationEventPublisher eventPublisher;

    public void send(SetEnergyTaskDto task) {
        log.info("Sending set energy task: trackId={}, energy={}, chatId={}", task.trackId(), task.energy(), task.chatId());
        eventPublisher.publishEvent(new SetEnergyTaskEvent(task));
    }
}

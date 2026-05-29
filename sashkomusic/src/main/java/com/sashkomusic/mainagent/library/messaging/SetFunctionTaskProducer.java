package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.SetFunctionTaskEvent;
import com.sashkomusic.mainagent.library.messaging.dto.SetFunctionTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class SetFunctionTaskProducer {

    private final ApplicationEventPublisher eventPublisher;

    public void send(SetFunctionTaskDto task) {
        log.info("Sending set function task: trackId={}, function={}, chatId={}", task.trackId(), task.function(), task.chatId());
        eventPublisher.publishEvent(new SetFunctionTaskEvent(task));
    }
}

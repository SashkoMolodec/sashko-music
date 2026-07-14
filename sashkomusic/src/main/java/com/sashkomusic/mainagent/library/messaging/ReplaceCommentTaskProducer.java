package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.ReplaceCommentTaskEvent;
import com.sashkomusic.mainagent.library.messaging.dto.ReplaceCommentTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReplaceCommentTaskProducer {

    private final ApplicationEventPublisher eventPublisher;

    public void send(ReplaceCommentTaskDto task) {
        log.info("Sending replace comment task: trackId={}, comment={}, chatId={}", task.trackId(), task.comment(), task.chatId());
        eventPublisher.publishEvent(new ReplaceCommentTaskEvent(task));
    }
}

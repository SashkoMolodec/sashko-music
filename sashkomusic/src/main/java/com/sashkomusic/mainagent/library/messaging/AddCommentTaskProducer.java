package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.AddCommentTaskEvent;
import com.sashkomusic.mainagent.library.messaging.dto.AddCommentTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class AddCommentTaskProducer {

    private final ApplicationEventPublisher eventPublisher;

    public void send(AddCommentTaskDto task) {
        log.info("Sending add comment task: trackId={}, comment={}, chatId={}", task.trackId(), task.comment(), task.chatId());
        eventPublisher.publishEvent(new AddCommentTaskEvent(task));
    }
}

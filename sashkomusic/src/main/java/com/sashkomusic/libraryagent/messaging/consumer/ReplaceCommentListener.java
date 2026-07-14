package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.events.ReplaceCommentTaskEvent;
import com.sashkomusic.libraryagent.domain.service.tag.RateTrackService;
import com.sashkomusic.libraryagent.messaging.producer.TrackUpdateResultProducer;
import com.sashkomusic.libraryagent.messaging.producer.dto.TrackUpdateResultDto;
import com.sashkomusic.mainagent.library.messaging.dto.ReplaceCommentTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReplaceCommentListener {

    private final RateTrackService rateTrackService;
    private final TrackUpdateResultProducer resultProducer;

    @EventListener
    @Async
    public void handle(ReplaceCommentTaskEvent event) {
        ReplaceCommentTaskDto task = event.payload();
        log.info("Received replace comment task: trackId={}, conversationId={}", task.trackId(), task.conversationId());
        try {
            RateTrackService.RateResult result = rateTrackService.replaceComment(task.trackId(), task.comment());
            resultProducer.send(new TrackUpdateResultDto(task.trackId(), "comment", task.comment(), result.success(), result.message(), task.conversationId()));
        } catch (Exception ex) {
            log.error("Error replacing comment: {}", ex.getMessage(), ex);
            resultProducer.send(new TrackUpdateResultDto(task.trackId(), "comment", task.comment(), false, "критична помилка: " + ex.getMessage(), task.conversationId()));
        }
    }
}

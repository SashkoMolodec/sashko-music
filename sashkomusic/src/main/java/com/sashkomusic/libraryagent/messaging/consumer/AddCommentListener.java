package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.events.AddCommentTaskEvent;
import com.sashkomusic.libraryagent.domain.service.tag.RateTrackService;
import com.sashkomusic.libraryagent.messaging.producer.TrackUpdateResultProducer;
import com.sashkomusic.libraryagent.messaging.producer.dto.TrackUpdateResultDto;
import com.sashkomusic.mainagent.library.messaging.dto.AddCommentTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class AddCommentListener {

    private final RateTrackService rateTrackService;
    private final TrackUpdateResultProducer resultProducer;

    @EventListener
    @Async
    public void handleAddComment(AddCommentTaskEvent event) {
        AddCommentTaskDto task = event.payload();
        log.info("Received add comment task: trackId={}, chatId={}", task.trackId(), task.chatId());

        try {
            RateTrackService.RateResult result = rateTrackService.addComment(task.trackId(), task.comment());
            resultProducer.send(new TrackUpdateResultDto(task.trackId(), "comment", task.comment(), result.success(), result.message(), task.chatId()));
        } catch (Exception ex) {
            log.error("Error adding comment: {}", ex.getMessage(), ex);
            resultProducer.send(new TrackUpdateResultDto(task.trackId(), "comment", task.comment(), false, "критична помилка: " + ex.getMessage(), task.chatId()));
        }
    }
}

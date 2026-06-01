package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.events.SetFunctionTaskEvent;
import com.sashkomusic.libraryagent.domain.service.tag.RateTrackService;
import com.sashkomusic.libraryagent.messaging.producer.TrackUpdateResultProducer;
import com.sashkomusic.libraryagent.messaging.producer.dto.TrackUpdateResultDto;
import com.sashkomusic.mainagent.library.messaging.dto.SetFunctionTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class SetFunctionListener {

    private final RateTrackService rateTrackService;
    private final TrackUpdateResultProducer resultProducer;

    @EventListener
    @Async
    public void handleSetFunction(SetFunctionTaskEvent event) {
        SetFunctionTaskDto task = event.payload();
        log.info("Received set function task: trackId={}, function={}, conversationId={}", task.trackId(), task.function(), task.conversationId());

        try {
            RateTrackService.RateResult result = rateTrackService.setFunction(task.trackId(), task.function());
            resultProducer.send(new TrackUpdateResultDto(task.trackId(), "function", task.function(), result.success(), result.message(), task.conversationId()));
        } catch (Exception ex) {
            log.error("Error setting function: {}", ex.getMessage(), ex);
            resultProducer.send(new TrackUpdateResultDto(task.trackId(), "function", task.function(), false, "критична помилка: " + ex.getMessage(), task.conversationId()));
        }
    }
}

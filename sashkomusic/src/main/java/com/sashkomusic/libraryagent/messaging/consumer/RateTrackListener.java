package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.events.RateTrackTaskEvent;
import com.sashkomusic.libraryagent.domain.service.tag.RateTrackService;
import com.sashkomusic.libraryagent.messaging.producer.TrackUpdateResultProducer;
import com.sashkomusic.libraryagent.messaging.producer.dto.TrackUpdateResultDto;
import com.sashkomusic.mainagent.library.messaging.dto.RateTrackTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class RateTrackListener {

    private final RateTrackService rateTrackService;
    private final TrackUpdateResultProducer resultProducer;

    @EventListener
    @Async
    public void handleRateTrack(RateTrackTaskEvent event) {
        RateTrackTaskDto task = event.payload();
        log.info("Received rate track task: trackId={}, rating={}, conversationId={}", task.trackId(), task.rating(), task.conversationId());

        try {
            RateTrackService.RateResult result = rateTrackService.rateTrack(task.trackId(), task.rating());
            resultProducer.send(new TrackUpdateResultDto(task.trackId(), "rating", String.valueOf(task.rating()), result.success(), result.message(), task.conversationId()));
        } catch (Exception ex) {
            log.error("Error rating track: {}", ex.getMessage(), ex);
            resultProducer.send(new TrackUpdateResultDto(task.trackId(), "rating", String.valueOf(task.rating()), false, "критична помилка: " + ex.getMessage(), task.conversationId()));
        }
    }
}

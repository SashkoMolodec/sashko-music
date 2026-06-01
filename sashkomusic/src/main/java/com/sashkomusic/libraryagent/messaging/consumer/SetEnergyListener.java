package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.events.SetEnergyTaskEvent;
import com.sashkomusic.libraryagent.domain.service.tag.RateTrackService;
import com.sashkomusic.libraryagent.messaging.producer.TrackUpdateResultProducer;
import com.sashkomusic.libraryagent.messaging.producer.dto.TrackUpdateResultDto;
import com.sashkomusic.mainagent.library.messaging.dto.SetEnergyTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class SetEnergyListener {

    private final RateTrackService rateTrackService;
    private final TrackUpdateResultProducer resultProducer;

    @EventListener
    @Async
    public void handleSetEnergy(SetEnergyTaskEvent event) {
        SetEnergyTaskDto task = event.payload();
        log.info("Received set energy task: trackId={}, energy={}, conversationId={}", task.trackId(), task.energy(), task.conversationId());

        try {
            RateTrackService.RateResult result = rateTrackService.setEnergy(task.trackId(), task.energy());
            resultProducer.send(new TrackUpdateResultDto(task.trackId(), "energy", task.energy(), result.success(), result.message(), task.conversationId()));
        } catch (Exception ex) {
            log.error("Error setting energy: {}", ex.getMessage(), ex);
            resultProducer.send(new TrackUpdateResultDto(task.trackId(), "energy", task.energy(), false, "критична помилка: " + ex.getMessage(), task.conversationId()));
        }
    }
}

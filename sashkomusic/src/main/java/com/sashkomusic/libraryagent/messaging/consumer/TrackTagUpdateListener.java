package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.events.AddCommentTaskEvent;
import com.sashkomusic.events.RateTrackTaskEvent;
import com.sashkomusic.events.ReplaceCommentTaskEvent;
import com.sashkomusic.events.SetEnergyTaskEvent;
import com.sashkomusic.events.SetFunctionTaskEvent;
import com.sashkomusic.events.TrackUpdateResultEvent;
import com.sashkomusic.libraryagent.domain.service.tag.RateTrackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@Slf4j
@RequiredArgsConstructor
public class TrackTagUpdateListener {

    private final RateTrackService rateTrackService;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    @Async("asyncExecutor")
    public void handleRateTrack(RateTrackTaskEvent event) {
        apply("rating", String.valueOf(event.rating()), event.trackId(), event.conversationId(),
                () -> rateTrackService.rateTrack(event.trackId(), event.rating()));
    }

    @EventListener
    @Async("asyncExecutor")
    public void handleAddComment(AddCommentTaskEvent event) {
        apply("comment", event.comment(), event.trackId(), event.conversationId(),
                () -> rateTrackService.addComment(event.trackId(), event.comment()));
    }

    @EventListener
    @Async("asyncExecutor")
    public void handleReplaceComment(ReplaceCommentTaskEvent event) {
        apply("comment", event.comment(), event.trackId(), event.conversationId(),
                () -> rateTrackService.replaceComment(event.trackId(), event.comment()));
    }

    @EventListener
    @Async("asyncExecutor")
    public void handleSetEnergy(SetEnergyTaskEvent event) {
        apply("energy", event.energy(), event.trackId(), event.conversationId(),
                () -> rateTrackService.setEnergy(event.trackId(), event.energy()));
    }

    @EventListener
    @Async("asyncExecutor")
    public void handleSetFunction(SetFunctionTaskEvent event) {
        apply("function", event.function(), event.trackId(), event.conversationId(),
                () -> rateTrackService.setFunction(event.trackId(), event.function()));
    }

    private void apply(String field, String value, Long trackId, String conversationId,
                       Supplier<RateTrackService.RateResult> action) {
        log.info("Received {} task: trackId={}, value={}, conversationId={}", field, trackId, value, conversationId);
        try {
            RateTrackService.RateResult result = action.get();
            publish(new TrackUpdateResultEvent(trackId, field, value, result.success(), result.message(), conversationId));
        } catch (Exception ex) {
            log.error("Error applying {} for trackId={}: {}", field, trackId, ex.getMessage(), ex);
            publish(new TrackUpdateResultEvent(trackId, field, value, false, "критична помилка: " + ex.getMessage(), conversationId));
        }
    }

    private void publish(TrackUpdateResultEvent result) {
        log.info("Sending track update result: trackId={}, field={}, value={}, success={}",
                result.trackId(), result.fieldUpdated(), result.value(), result.success());
        eventPublisher.publishEvent(result);
    }
}

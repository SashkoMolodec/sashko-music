package com.sashkomusic.libraryagent.api;

import com.sashkomusic.events.TrackAnalysisCompleteEvent;
import com.sashkomusic.libraryagent.messaging.consumer.dto.TrackAnalysisCompleteDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
@Slf4j
@RequiredArgsConstructor
public class AudioAnalyzerCallbackController {

    private final ApplicationEventPublisher eventPublisher;

    @PostMapping("/audio-analysis-complete")
    public void onAnalysisComplete(@RequestBody TrackAnalysisCompleteDto dto) {
        log.info("Received audio analysis callback for trackId={}, success={}", dto.trackId(), dto.success());
        eventPublisher.publishEvent(new TrackAnalysisCompleteEvent(dto));
    }
}

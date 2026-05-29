package com.sashkomusic.libraryagent.messaging.producer;

import com.sashkomusic.libraryagent.messaging.producer.dto.AnalyzeTrackTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@Slf4j
@RequiredArgsConstructor
public class AnalyzeTrackProducer {

    private final WebClient.Builder webClientBuilder;

    @Value("${audio-analyzer.url:http://localhost:8090}")
    private String audioAnalyzerUrl;

    public void sendAnalysisTask(AnalyzeTrackTaskDto task) {
        log.info("Sending track analysis task for trackId={}, path={}", task.trackId(), task.localPath());

        webClientBuilder.build()
                .post()
                .uri(audioAnalyzerUrl + "/analyze")
                .bodyValue(task)
                .retrieve()
                .toBodilessEntity()
                .subscribe(
                        response -> log.debug("Analysis task sent for trackId={}", task.trackId()),
                        error -> log.error("Failed to send analysis task for trackId={}: {}", task.trackId(), error.getMessage())
                );
    }
}

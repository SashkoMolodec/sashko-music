package com.sashkomusic.libraryagent.client;

import com.sashkomusic.libraryagent.client.dto.AnalyzeTrackRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@Slf4j
@RequiredArgsConstructor
public class AudioAnalyzerClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${audio-analyzer.url:http://localhost:8090}")
    private String audioAnalyzerUrl;

    public void requestAnalysis(AnalyzeTrackRequest request) {
        log.info("Sending track analysis task for trackId={}, path={}", request.trackId(), request.localPath());
        webClientBuilder.build()
                .post()
                .uri(audioAnalyzerUrl + "/analyze")
                .bodyValue(request)
                .retrieve()
                .toBodilessEntity()
                .subscribe(
                        response -> log.debug("Analysis task sent for trackId={}", request.trackId()),
                        error -> log.error("Failed to send analysis task for trackId={}: {}", request.trackId(), error.getMessage())
                );
    }
}

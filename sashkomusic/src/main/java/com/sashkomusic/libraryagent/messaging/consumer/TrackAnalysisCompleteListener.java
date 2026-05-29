package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.events.TrackAnalysisCompleteEvent;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.entity.TrackAnalysis;
import com.sashkomusic.libraryagent.domain.repository.TrackAnalysisRepository;
import com.sashkomusic.libraryagent.domain.repository.TrackRepository;
import com.sashkomusic.libraryagent.domain.service.processFolder.TrackAnalysisJsonReader;
import com.sashkomusic.libraryagent.messaging.consumer.dto.TrackAnalysisCompleteDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@Slf4j
@RequiredArgsConstructor
public class TrackAnalysisCompleteListener {

    private final TrackRepository trackRepository;
    private final TrackAnalysisRepository analysisRepository;
    private final TrackAnalysisJsonReader jsonReader;

    @EventListener
    @Async
    @Transactional
    public void handleAnalysisComplete(TrackAnalysisCompleteEvent event) {
        TrackAnalysisCompleteDto message = event.payload();
        log.info("Received analysis complete for trackId={}, success={}, jsonPath={}",
                message.trackId(), message.success(), message.jsonResultPath());

        try {
            Track track = trackRepository.findById(message.trackId())
                    .orElseThrow(() -> new IllegalStateException("Track not found: " + message.trackId()));

            TrackAnalysis analysis = analysisRepository.findByTrackId(message.trackId())
                    .orElseGet(() -> new TrackAnalysis(track));

            if (message.success() && message.jsonResultPath() != null) {
                try {
                    TrackAnalysis parsedAnalysis = jsonReader.readAnalysisFromJson(message.jsonResultPath(), track);
                    copyAnalysisFields(parsedAnalysis, analysis);
                    log.info("Successfully loaded analysis from JSON for trackId={} (BPM: {}, Danceability: {})",
                            message.trackId(), analysis.getBpm(), analysis.getDanceability());
                } catch (Exception e) {
                    log.error("Failed to read JSON file {}: {}", message.jsonResultPath(), e.getMessage(), e);
                    analysis.setErrorMessage("Failed to read JSON: " + e.getMessage());
                }
            } else {
                String errorMsg = message.errorMessage() != null ? message.errorMessage() : "Unknown error during analysis";
                analysis.setErrorMessage(errorMsg);
                log.warn("Analysis failed for trackId={}: {}", message.trackId(), errorMsg);
            }

            analysis.setAnalyzedAt(LocalDateTime.now());
            analysisRepository.save(analysis);
            log.info("Saved track analysis for trackId={}, hasError={}", message.trackId(), analysis.hasError());

        } catch (Exception ex) {
            log.error("Failed to process analysis result for trackId={}: {}", message.trackId(), ex.getMessage(), ex);
        }
    }

    private void copyAnalysisFields(TrackAnalysis source, TrackAnalysis target) {
        target.setBpm(source.getBpm());
        target.setDanceability(source.getDanceability());
        target.setBeatsLoudness(source.getBeatsLoudness());
        target.setOnsetRate(source.getOnsetRate());
        target.setMfcc1Mean(source.getMfcc1Mean()); target.setMfcc1Var(source.getMfcc1Var());
        target.setMfcc2Mean(source.getMfcc2Mean()); target.setMfcc2Var(source.getMfcc2Var());
        target.setMfcc3Mean(source.getMfcc3Mean()); target.setMfcc3Var(source.getMfcc3Var());
        target.setMfcc4Mean(source.getMfcc4Mean()); target.setMfcc4Var(source.getMfcc4Var());
        target.setMfcc5Mean(source.getMfcc5Mean()); target.setMfcc5Var(source.getMfcc5Var());
        target.setMfcc6Mean(source.getMfcc6Mean()); target.setMfcc6Var(source.getMfcc6Var());
        target.setMfcc7Mean(source.getMfcc7Mean()); target.setMfcc7Var(source.getMfcc7Var());
        target.setMfcc8Mean(source.getMfcc8Mean()); target.setMfcc8Var(source.getMfcc8Var());
        target.setMfcc9Mean(source.getMfcc9Mean()); target.setMfcc9Var(source.getMfcc9Var());
        target.setMfcc10Mean(source.getMfcc10Mean()); target.setMfcc10Var(source.getMfcc10Var());
        target.setMfcc11Mean(source.getMfcc11Mean()); target.setMfcc11Var(source.getMfcc11Var());
        target.setMfcc12Mean(source.getMfcc12Mean()); target.setMfcc12Var(source.getMfcc12Var());
        target.setMfcc13Mean(source.getMfcc13Mean()); target.setMfcc13Var(source.getMfcc13Var());
        target.setSpectralCentroid(source.getSpectralCentroid());
        target.setSpectralRolloff(source.getSpectralRolloff());
        target.setDissonance(source.getDissonance());
        target.setLoudness(source.getLoudness());
        target.setDynamicComplexity(source.getDynamicComplexity());
        target.setErrorMessage(source.getErrorMessage());
        target.setAnalysisVersion(source.getAnalysisVersion());
    }
}

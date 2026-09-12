package com.sashkomusic.events;

public record TrackAnalysisCompleteEvent(
        Long trackId,
        String jsonResultPath,
        boolean success,
        String errorMessage
) {}

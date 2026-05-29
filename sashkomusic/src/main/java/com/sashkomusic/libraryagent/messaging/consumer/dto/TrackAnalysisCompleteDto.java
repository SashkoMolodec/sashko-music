package com.sashkomusic.libraryagent.messaging.consumer.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("track_analysis_complete")
public record TrackAnalysisCompleteDto(
        Long trackId,
        String jsonResultPath,
        boolean success,
        String errorMessage
) {
}

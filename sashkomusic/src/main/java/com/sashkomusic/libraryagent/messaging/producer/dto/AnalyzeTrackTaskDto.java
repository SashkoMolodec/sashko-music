package com.sashkomusic.libraryagent.messaging.producer.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("analyze_track_task")
public record AnalyzeTrackTaskDto(
        Long trackId,
        String localPath,
        Long releaseId,
        String releaseTitle,
        String trackTitle
) {
}

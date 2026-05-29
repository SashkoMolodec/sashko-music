package com.sashkomusic.libraryagent.messaging.producer.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("track_update_result")
public record TrackUpdateResultDto(
        Long trackId,
        String fieldUpdated,  // "rating", "energy", "function", "comment"
        String value,         // "5", "E3", "banger", "some comment"
        boolean success,
        String message,
        long chatId
) {
}

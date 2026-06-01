package com.sashkomusic.libraryagent.messaging.producer.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("track_update_result")
public record TrackUpdateResultDto(
        Long trackId,
        String fieldUpdated,  // "rating", "energy", "function", "comment"
        String value,         // "5", "E3", "banger", "some comment"
        boolean success,
        String message,
        String conversationId
) {
    public long chatId() {
        int colon = conversationId.indexOf(':');
        return Long.parseLong(colon < 0 ? conversationId : conversationId.substring(0, colon));
    }
}

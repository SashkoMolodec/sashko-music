package com.sashkomusic.mainagent.library.messaging.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("rate_track_task")
public record RateTrackTaskDto(
        Long trackId,
        int rating,
        long chatId
) {
}

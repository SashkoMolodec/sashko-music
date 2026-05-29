package com.sashkomusic.api.dto;

import java.util.Map;

public record TrackFilterRequest(Map<String, String> tags) {
    public TrackFilterRequest {
        if (tags == null) {
            tags = Map.of();
        }
    }
}

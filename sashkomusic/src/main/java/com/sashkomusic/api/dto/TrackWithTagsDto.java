package com.sashkomusic.api.dto;

import java.util.Map;

public record TrackWithTagsDto(
        Long id,
        String path,
        String title,
        String artistName,
        Map<String, String> tags
) {
    public static TrackWithTagsDto of(Long id, String path, String title, String artistName, Map<String, String> tags) {
        return new TrackWithTagsDto(id, path, title, artistName, tags);
    }
}

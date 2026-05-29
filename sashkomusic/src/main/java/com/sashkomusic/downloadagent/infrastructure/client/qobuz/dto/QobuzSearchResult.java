package com.sashkomusic.downloadagent.infrastructure.client.qobuz.dto;

import java.util.List;

/**
 * Represents a Qobuz album search result
 */
public record QobuzSearchResult(
        String albumId,
        String title,
        String artist,
        String releaseDate,
        String url,
        int trackCount,
        List<Integer> availableQualities,
        String coverUrl
) {
    public static QobuzSearchResult fromParsedData(
            String albumId,
            String title,
            String artist,
            String releaseDate,
            int trackCount
    ) {
        // Generate Qobuz URL from album ID
        String url = "https://www.qobuz.com/interpreter/album/" + albumId;

        return new QobuzSearchResult(
                albumId,
                title,
                artist,
                releaseDate,
                url,
                trackCount,
                List.of(27, 7, 6, 5), // Default: assume all qualities available
                null // Cover URL can be fetched separately if needed
        );
    }
}

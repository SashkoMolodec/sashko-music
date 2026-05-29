package com.sashkomusic.downloadagent.infrastructure.client.bandcamp;

public record BandcampSearchResult(
        String artist,
        String title,
        String type,
        String url
) {
}

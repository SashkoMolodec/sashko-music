package com.sashkomusic.mainagent.search.client.bandcamp;

import java.util.List;

public record BandcampSearchResponse(List<Result> results) {
    public record Result(
            String artist,
            String title,
            String type,
            String url,
            String imageUrl,
            String year,
            List<String> tags
    ) {
    }
}

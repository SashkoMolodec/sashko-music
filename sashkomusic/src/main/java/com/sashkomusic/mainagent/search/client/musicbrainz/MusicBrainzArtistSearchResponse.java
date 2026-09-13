package com.sashkomusic.mainagent.search.client.musicbrainz;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MusicBrainzArtistSearchResponse(
        int count,
        List<Artist> artists
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Artist(
            String id,
            String name,
            int score
    ) {
    }
}

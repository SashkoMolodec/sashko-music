package com.sashkomusic.mainagent.search.client.listenbrainz;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ListenBrainzSimilarArtistsResponse(
        @JsonProperty("artist_mbid")
        String artistMbid,

        String name,
        String comment,
        String type,
        int score
) {
}

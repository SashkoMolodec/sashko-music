package com.sashkomusic.mainagent.search.client.musicbrainz;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record RecordingSearchResponse(
        List<Recording> recordings
) {
    public record Recording(
            String id,
            int score,
            String title,
            @JsonProperty("artist-credit")
            List<ArtistCredit> artistCredit,
            List<Release> releases
    ) {
    }

    public record Release(
            String id,
            String title,
            @JsonProperty("status-id")
            String statusId,
            String status,
            String date,
            String country,
            @JsonProperty("release-group")
            ReleaseGroup releaseGroup,
            @JsonProperty("track-count")
            Integer trackCount,
            List<Media> media,
            @JsonProperty("artist-credit")
            List<ArtistCredit> artistCredit
    ) {
    }

    public record ReleaseGroup(
            String id,
            String title,
            @JsonProperty("primary-type")
            String primaryType,
            @JsonProperty("secondary-types")
            List<String> secondaryTypes
    ) {
    }

    public record Media(
            String id,
            int position,
            String format,
            @JsonProperty("track-count")
            Integer trackCount
    ) {
    }

    public record ArtistCredit(
            String name,
            Artist artist,
            String joinphrase
    ) {
    }

    public record Artist(
            String id,
            String name,
            @JsonProperty("sort-name")
            String sortName
    ) {
    }
}

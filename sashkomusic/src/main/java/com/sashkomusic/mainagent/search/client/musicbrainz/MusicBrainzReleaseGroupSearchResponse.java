package com.sashkomusic.mainagent.search.client.musicbrainz;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response shape for GET /release-group/?query=... — used for BROWSE-style searches
 * (style/genre + year, with no specific artist/title known). Release groups are the
 * canonical "album concept" MusicBrainz entity: one row per album instead of one row
 * per pressing, so results are already deduplicated and far less noisy than searching
 * /release directly with the same tag/date filter.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MusicBrainzReleaseGroupSearchResponse(
        String created,
        int count,
        int offset,

        @JsonProperty("release-groups")
        List<ReleaseGroup> releaseGroups
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReleaseGroup(
            String id,
            int score,

            @JsonProperty("primary-type")
            String primaryType,

            @JsonProperty("secondary-types")
            List<String> secondaryTypes,

            String title,

            @JsonProperty("first-release-date")
            String firstReleaseDate,

            @JsonProperty("artist-credit")
            List<MusicBrainzSearchResponse.ArtistCredit> artistCredit,

            List<Release> releases,

            List<MusicBrainzSearchResponse.Tag> tags
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Release(
            String id,
            String title,
            String status
    ) {
    }
}

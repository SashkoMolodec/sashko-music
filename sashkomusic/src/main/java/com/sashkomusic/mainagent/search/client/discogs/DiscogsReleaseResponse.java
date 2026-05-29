package com.sashkomusic.mainagent.search.client.discogs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DiscogsReleaseResponse(
        Long id,
        String title,
        List<Artist> artists,
        List<Track> tracklist,
        @JsonProperty("main_release") Long mainRelease,
        Integer year,
        List<String> genres,
        List<String> styles,
        List<Format> formats,
        List<Label> labels,
        List<Image> images
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Artist(
            String name,
            String join  // How to join with next artist (e.g., "&", "feat.")
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Track(
            String position,
            String title,
            String duration,
            @JsonProperty("type_") String type,
            List<Artist> artists
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Format(
            String name,
            String qty,
            List<String> descriptions
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Label(
            String name,
            String catno
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Image(
            String type,
            String uri,
            @JsonProperty("resource_url") String resourceUrl,
            String uri150,
            Integer width,
            Integer height
    ) {
    }
}

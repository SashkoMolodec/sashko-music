package com.sashkomusic.mainagent.search.client.discogs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DiscogsSearchResponse(
        List<Result> results
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            Long id,
            String type,  // "master", "release"
            String title,
            String country,
            List<String> format,
            List<String> label,
            String year,
            List<String> genre,
            List<String> style,
            @JsonProperty("cover_image") String coverImage,
            @JsonProperty("thumb") String thumb,
            @JsonProperty("resource_url") String resourceUrl,
            @JsonProperty("master_id") Long masterId,
            @JsonProperty("master_url") String masterUrl
    ) {
    }
}
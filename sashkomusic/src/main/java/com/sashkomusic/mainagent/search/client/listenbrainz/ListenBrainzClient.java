package com.sashkomusic.mainagent.search.client.listenbrainz;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * ListenBrainz Labs similar-artists lookup — free, no API key, no registration, CC0-licensed.
 * Backs the "I have this release, find me something similar" feature: resolve the seed artist's
 * MBID (via MusicBrainzClient.findArtistMbid), then ask this endpoint for the artists most
 * frequently co-listened with it.
 */
@Slf4j
@Component
public class ListenBrainzClient {

    private static final String ALGORITHM =
            "session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30";

    private final RestClient client;

    public ListenBrainzClient(RestClient.Builder builder) {
        this.client = builder.baseUrl("https://labs.api.listenbrainz.org").build();
    }

    @CircuitBreaker(name = "listenBrainzClient", fallbackMethod = "findSimilarArtistsFallback")
    @Retry(name = "listenBrainzClient")
    public List<ListenBrainzSimilarArtistsResponse> findSimilarArtists(String artistMbid) {
        List<ListenBrainzSimilarArtistsResponse> response = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/similar-artists/json")
                        .queryParam("artist_mbids", artistMbid)
                        .queryParam("algorithm", ALGORITHM)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return response != null ? response : List.of();
    }

    public List<ListenBrainzSimilarArtistsResponse> findSimilarArtistsFallback(String artistMbid, Exception e) {
        log.warn("ListenBrainz findSimilarArtists fallback triggered for MBID '{}': {}", artistMbid, e.getMessage());
        return List.of();
    }
}

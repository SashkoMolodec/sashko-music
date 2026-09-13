package com.sashkomusic.mainagent.search.client.ytmusic;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin client for sm-scraper's /ytmusic/search — resolves an artist+album pair to a real
 * YouTube Music album/playlist link. Used as a fallback listen-link source for releases that
 * don't carry a direct link of their own (MusicBrainz results, Discogs releases with no
 * community video attached).
 */
@Slf4j
@Component
public class YtMusicScraperClient {

    private final RestClient client;

    public YtMusicScraperClient(RestClient.Builder builder, @Value("${sm.scraper.url}") String scraperUrl) {
        this.client = builder.baseUrl(scraperUrl).build();
    }

    @CircuitBreaker(name = "ytMusicScraperClient", fallbackMethod = "findAlbumUrlFallback")
    @Retry(name = "ytMusicScraperClient")
    public Optional<String> findAlbumUrl(String artist, String album) {
        List<Map<String, Object>> results = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ytmusic/search")
                        .queryParam("artist", artist)
                        .queryParam("album", album)
                        .queryParam("limit", 1)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (results == null || results.isEmpty()) {
            return Optional.empty();
        }
        var top = results.getFirst();
        String playlistId = (String) top.get("playlistId");
        if (playlistId != null && !playlistId.isBlank()) {
            return Optional.of("https://music.youtube.com/playlist?list=" + playlistId);
        }
        String videoId = (String) top.get("videoId");
        if (videoId != null && !videoId.isBlank()) {
            return Optional.of("https://music.youtube.com/watch?v=" + videoId);
        }
        return Optional.empty();
    }

    public Optional<String> findAlbumUrlFallback(String artist, String album, Exception e) {
        log.warn("yt-music scraper search failed for '{} - {}': {}", artist, album, e.getMessage());
        return Optional.empty();
    }
}

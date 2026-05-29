package com.sashkomusic.mainagent.library.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sashkomusic.mainagent.library.config.NavidromeConfig;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

@Component
public class NavidromeClient {

    private static final Logger log = LoggerFactory.getLogger(NavidromeClient.class);

    private final RestClient restClient;
    private final NavidromeConfig config;

    public NavidromeClient(RestClient.Builder restClientBuilder, NavidromeConfig config) {
        this.restClient = restClientBuilder.build();
        this.config = config;
    }

    public void triggerScan(String folderPath) {
        try {
            URI uri = buildScanUri(folderPath);

            log.info("Triggering Navidrome scan for folder: {}", folderPath);
            log.debug("Scan request URL: {}", uri);

            restClient.get()
                    .uri(uri)
                    .retrieve()
                    .toBodilessEntity();

            log.info("✓ Successfully triggered Navidrome scan for: {}", folderPath);

        } catch (Exception e) {
            log.warn("⚠️  Failed to trigger Navidrome scan for {}: {}. Navidrome will scan automatically on schedule.",
                    folderPath, e.getMessage());
            log.debug("Navidrome scan error details", e);
        }
    }

    public void triggerFullScan() {
        triggerScan(null);
    }

    public record CurrentTrackInfo(String navidromeId, String artist, String title) {
    }

    @CircuitBreaker(name = "navidromeClient", fallbackMethod = "getCurrentlyPlayingTrackInfoFallback")
    @Retry(name = "navidromeClient")
    public CurrentTrackInfo getCurrentlyPlayingTrackInfo() {
        try {
            URI uri = buildCurrentlyPlayingUri();

            log.info("Fetching currently playing track info from Navidrome");

            NavidromeNowPlayingResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(NavidromeNowPlayingResponse.class);

            if (response != null &&
                    response.subsonicResponse() != null &&
                    response.subsonicResponse().nowPlaying() != null &&
                    response.subsonicResponse().nowPlaying().entry() != null &&
                    !response.subsonicResponse().nowPlaying().entry().isEmpty()) {

                NavidromeNowPlayingResponse.Entry entry = response.subsonicResponse().nowPlaying().entry().getFirst();
                log.info("✓ Successfully fetched currently playing track: {} - {}", entry.artist(), entry.title());
                return new CurrentTrackInfo(entry.id(), entry.artist(), entry.title());
            }

            log.warn("No tracks currently playing in Navidrome");
            return null;

        } catch (Exception e) {
            log.error("Failed to fetch currently playing track info: {}", e.getMessage());
            throw e;
        }
    }

    private CurrentTrackInfo getCurrentlyPlayingTrackInfoFallback(Exception e) {
        log.warn("Navidrome getCurrentlyPlayingTrackInfo fallback triggered: {}", e.getMessage());
        return null;
    }

    private URI buildScanUri(String folderPath) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(config.getBaseUrl())
                .path("/rest/startScan")
                .queryParam("u", config.getUsername())
                .queryParam("p", config.getPassword())
                .queryParam("v", config.getApiVersion())
                .queryParam("c", config.getClientName())
                .queryParam("f", "json");

        if (folderPath != null && !folderPath.isEmpty()) {
            builder.queryParam("target", config.getLibraryId() + ":" + folderPath);
        }

        return builder.build().toUri();
    }

    private URI buildCurrentlyPlayingUri() {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(config.getBaseUrl())
                .path("/rest/getNowPlaying")
                .queryParam("u", config.getUsername())
                .queryParam("p", config.getPassword())
                .queryParam("v", config.getApiVersion())
                .queryParam("c", config.getClientName())
                .queryParam("f", "json");

        return builder.build().toUri();
    }

    @CircuitBreaker(name = "navidromeClient", fallbackMethod = "setRatingFallback")
    @Retry(name = "navidromeClient")
    public void setRating(String navidromeId, int rating) {
        try {
            URI uri = buildSetRatingUri(navidromeId, rating);

            log.info("Setting rating {} for Navidrome track id: {}", rating, navidromeId);

            restClient.get()
                    .uri(uri)
                    .retrieve()
                    .toBodilessEntity();

            log.info("✓ Successfully set rating {} for Navidrome track: {}", rating, navidromeId);

        } catch (Exception e) {
            log.error("Failed to set rating for Navidrome track {}: {}", navidromeId, e.getMessage());
            throw e;
        }
    }

    private void setRatingFallback(String navidromeId, int rating, Exception e) {
        log.warn("Navidrome setRating fallback triggered for track {} with rating {}: {}",
            navidromeId, rating, e.getMessage());
    }

    @CircuitBreaker(name = "navidromeClient", fallbackMethod = "findTrackIdByArtistAndTitleFallback")
    @Retry(name = "navidromeClient")
    public String findTrackIdByArtistAndTitle(String artist, String title) {
        try {
            String query = artist + " " + title;
            URI uri = buildSearchUri(query);

            log.info("Searching for track in Navidrome: {} - {}", artist, title);

            NavidromeSearchResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(NavidromeSearchResponse.class);

            if (response != null &&
                    response.subsonicResponse() != null &&
                    response.subsonicResponse().searchResult3() != null &&
                    response.subsonicResponse().searchResult3().song() != null &&
                    !response.subsonicResponse().searchResult3().song().isEmpty()) {

                String trackId = response.subsonicResponse().searchResult3().song().getFirst().id();
                log.info("✓ Found Navidrome track id: {} for {} - {}", trackId, artist, title);
                return trackId;
            }

            log.warn("Track not found in Navidrome: {} - {}", artist, title);
            return null;

        } catch (Exception e) {
            log.error("Failed to search track in Navidrome: {} - {}", artist, title);
            throw e;
        }
    }

    private String findTrackIdByArtistAndTitleFallback(String artist, String title, Exception e) {
        log.warn("Navidrome findTrackIdByArtistAndTitle fallback triggered for {} - {}: {}",
            artist, title, e.getMessage());
        return null;
    }

    private URI buildSetRatingUri(String navidromeId, int rating) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(config.getBaseUrl())
                .path("/rest/setRating")
                .queryParam("id", navidromeId)
                .queryParam("rating", rating)
                .queryParam("u", config.getUsername())
                .queryParam("p", config.getPassword())
                .queryParam("v", config.getApiVersion())
                .queryParam("c", config.getClientName())
                .queryParam("f", "json");

        return builder.build().toUri();
    }

    private URI buildSearchUri(String query) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(config.getBaseUrl())
                .path("/rest/search3")
                .queryParam("query", query)
                .queryParam("u", config.getUsername())
                .queryParam("p", config.getPassword())
                .queryParam("v", config.getApiVersion())
                .queryParam("c", config.getClientName())
                .queryParam("f", "json");

        return builder.build().toUri();
    }

    public record NavidromeNowPlayingResponse(
            @JsonProperty("subsonic-response") SubsonicResponse subsonicResponse
    ) {
        public record SubsonicResponse(NowPlaying nowPlaying) {
        }

        public record NowPlaying(List<Entry> entry) {
        }

        public record Entry(String id, String path, String artist, String title) {
        }
    }

    public record NavidromeSearchResponse(
            @JsonProperty("subsonic-response") SubsonicResponse subsonicResponse
    ) {
        public record SubsonicResponse(SearchResult3 searchResult3) {
        }

        public record SearchResult3(List<Song> song) {
        }

        public record Song(String id, String title, String artist) {
        }
    }
}
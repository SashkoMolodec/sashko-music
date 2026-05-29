package com.sashkomusic.downloadagent.infrastructure.client.applemusic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ITunesSearchClient {

    private static final String ITUNES_SEARCH_API = "https://itunes.apple.com/search";
    private static final int MAX_RESULTS = 10;

    private final RestClient restClient;

    public ITunesSearchClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(objectMapper);
        converter.setSupportedMediaTypes(List.of(
                MediaType.APPLICATION_JSON,
                new MediaType("text", "javascript")
        ));

        this.restClient = restClientBuilder
                .baseUrl(ITUNES_SEARCH_API)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                    converters.add(converter);
                })
                .build();
    }

    @CircuitBreaker(name = "itunesSearchClient", fallbackMethod = "searchFallback")
    @Retry(name = "itunesSearchClient")
    public List<AppleMusicSearchResult> search(String artist, String title) {
        log.info("Searching Apple Music: artist={}, title={}", artist, title);

        String searchTerm = buildSearchTerm(artist, title);

        try {
            iTunesSearchResponse searchResponse = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .queryParam("term", searchTerm)
                            .queryParam("entity", "album")
                            .queryParam("limit", 10)
                            .build())
                    .retrieve()
                    .body(iTunesSearchResponse.class);

            if (searchResponse == null || searchResponse.results == null || searchResponse.results.isEmpty()) {
                log.info("No results found in iTunes for: {}", searchTerm);
                return Collections.emptyList();
            }

            log.info("Found {} results from iTunes API", searchResponse.results.size());

            return searchResponse.results.stream()
                    .limit(MAX_RESULTS)
                    .map(this::toSearchResult)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error searching Apple Music: artist={}, title={}", artist, title, e);
            throw e;
        }
    }

    private List<AppleMusicSearchResult> searchFallback(String artist, String title, Exception e) {
        log.warn("iTunes search fallback triggered for '{}' - '{}': {}", artist, title, e.getMessage());
        return Collections.emptyList();
    }

    private String buildSearchTerm(String artist, String title) {
        String combined = (artist + " " + title).trim();
        return combined.replace(" ", "+");
    }

    private AppleMusicSearchResult toSearchResult(iTunesAlbum album) {
        String displayName = String.format("%s - %s (%d tracks)",
                album.artistName,
                album.collectionName,
                album.trackCount != null ? album.trackCount : 0);

        return new AppleMusicSearchResult(
                String.valueOf(album.collectionId),
                album.collectionViewUrl,
                album.artistName,
                album.collectionName,
                displayName,
                album.trackCount != null ? album.trackCount : 0
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class iTunesSearchResponse {
        public int resultCount;
        public List<iTunesAlbum> results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class iTunesAlbum {
        public String wrapperType;
        public String collectionType;
        public long collectionId;
        public String artistName;
        public String collectionName;
        public String collectionViewUrl;
        public Integer trackCount;
    }

    public record AppleMusicSearchResult(
            String id,
            String url,
            String artistName,
            String albumName,
            String displayName,
            int trackCount
    ) {
    }
}

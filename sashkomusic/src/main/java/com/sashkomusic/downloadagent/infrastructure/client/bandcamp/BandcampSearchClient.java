package com.sashkomusic.downloadagent.infrastructure.client.bandcamp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class BandcampSearchClient {

    private static final String BANDCAMP_API_PATH = "/api/bcsearch_public_api/1/autocomplete_elastic";

    private final RestClient scraperClient;
    private final RestClient bandcampClient;

    public BandcampSearchClient(RestClient.Builder builder, @Value("${sm.scraper.url}") String scraperUrl) {
        this.scraperClient = builder.baseUrl(scraperUrl).build();
        this.bandcampClient = builder
                .baseUrl("https://bandcamp.com")
                .defaultHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public List<BandcampSearchResult> search(String artist, String release) {
        String query = artist + " " + release;

        List<BandcampSearchResult> results = searchViaScraper(query);
        if (!results.isEmpty()) {
            log.info("Bandcamp scraper returned {} results for download search", results.size());
            return results;
        }

        log.info("Scraper returned nothing, falling back to Bandcamp API for query='{}'", query);
        return searchViaApi(query);
    }

    private List<BandcampSearchResult> searchViaScraper(String query) {
        try {
            log.info("Searching Bandcamp via scraper: query='{}'", query);
            List<Map<String, Object>> results = scraperClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/bandcamp/search").queryParam("q", query).build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (results == null || results.isEmpty()) return List.of();
            return results.stream()
                    .map(r -> new BandcampSearchResult(
                            (String) r.getOrDefault("artist", "Unknown Artist"),
                            (String) r.getOrDefault("title", ""),
                            (String) r.getOrDefault("type", "album"),
                            (String) r.getOrDefault("url", "")))
                    .filter(r -> !r.title().isEmpty() && !r.url().isEmpty() && "album".equalsIgnoreCase(r.type()))
                    .limit(10)
                    .toList();
        } catch (Exception e) {
            log.warn("Bandcamp scraper unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    private List<BandcampSearchResult> searchViaApi(String query) {
        try {
            log.info("Searching Bandcamp API: query='{}'", query);
            var body = Map.of("search_text", query, "search_filter", "a", "full_page", true);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = bandcampClient.post()
                    .uri(BANDCAMP_API_PATH)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null) return List.of();

            @SuppressWarnings("unchecked")
            Map<String, Object> auto = (Map<String, Object>) response.get("auto");
            if (auto == null) return List.of();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) auto.get("results");
            if (items == null || items.isEmpty()) {
                log.info("No Bandcamp API results for query: {}", query);
                return List.of();
            }

            List<BandcampSearchResult> results = items.stream()
                    .map(item -> new BandcampSearchResult(
                            (String) item.getOrDefault("band_name", "Unknown Artist"),
                            (String) item.getOrDefault("name", ""),
                            "album",
                            (String) item.getOrDefault("item_url_path", "")))
                    .filter(r -> !r.title().isEmpty() && !r.url().isEmpty())
                    .limit(10)
                    .toList();

            log.info("Bandcamp API returned {} results", results.size());
            return results;
        } catch (Exception ex) {
            log.warn("Bandcamp API fallback failed: {}", ex.getMessage());
            return List.of();
        }
    }
}

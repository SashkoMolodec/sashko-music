package com.sashkomusic.mainagent.search.client.bandcamp;

import com.sashkomusic.libraryagent.domain.model.ReleaseMetadataFile;
import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.search.SearchEngineService;
import com.sashkomusic.mainagent.shared.model.MetadataSearchRequest;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.mainagent.shared.model.TrackMetadata;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class BandcampClient implements SearchEngineService {

    private final RestClient scraperClient;
    private final RestClient bandcampClient;

    public BandcampClient(
            RestClient.Builder builder,
            @Value("${sm.scraper.url}") String scraperUrl) {
        this.scraperClient = builder.baseUrl(scraperUrl).build();
        this.bandcampClient = builder
                .baseUrl("https://bandcamp.com")
                .defaultHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @CircuitBreaker(name = "bandcampClient", fallbackMethod = "searchReleasesFallback")
    @Retry(name = "bandcampClient")
    @Override
    public List<ReleaseMetadata> searchReleases(MetadataSearchRequest request) {
        String query = buildSearchQuery(request);

        List<BandcampSearchResponse.Result> results = searchViaScraper(query);
        if (!results.isEmpty()) {
            log.info("Bandcamp scraper returned {} results", results.size());
            return mapToDomain(results);
        }

        log.info("Scraper returned nothing, falling back to Bandcamp API for query='{}'", query);
        results = searchViaApi(query);
        log.info("Bandcamp API returned {} results", results.size());
        return mapToDomain(results);
    }

    private List<BandcampSearchResponse.Result> searchViaScraper(String query) {
        try {
            log.info("Searching Bandcamp via sm-scraper: query='{}'", query);
            List<BandcampSearchResponse.Result> results = scraperClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/bandcamp/search").queryParam("q", query).build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return results != null ? results : List.of();
        } catch (Exception e) {
            log.warn("Bandcamp scraper unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    private List<BandcampSearchResponse.Result> searchViaApi(String query) {
        try {
            var body = Map.of("search_text", query, "search_filter", "a", "full_page", true);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = bandcampClient.post()
                    .uri("/api/bcsearch_public_api/1/autocomplete_elastic")
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null) return List.of();

            @SuppressWarnings("unchecked")
            var auto = (Map<String, Object>) response.get("auto");
            if (auto == null) return List.of();

            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) auto.get("results");
            if (items == null) return List.of();

            return items.stream()
                    .map(item -> new BandcampSearchResponse.Result(
                            (String) item.getOrDefault("band_name", "Unknown Artist"),
                            (String) item.getOrDefault("name", ""),
                            "album",
                            (String) item.getOrDefault("item_url_path", ""),
                            (String) item.getOrDefault("img", ""),
                            "",
                            List.of()
                    ))
                    .filter(r -> !r.title().isEmpty() && !r.url().isEmpty())
                    .limit(10)
                    .toList();
        } catch (Exception e) {
            log.warn("Bandcamp API fallback failed: {}", e.getMessage());
            return List.of();
        }
    }

    public List<ReleaseMetadata> searchReleasesFallback(MetadataSearchRequest request, Exception e) {
        log.warn("Bandcamp searchReleases fallback triggered for query '{}': {}",
                buildSearchQuery(request), e.getMessage());
        return List.of();
    }

    @Override
    public List<TrackMetadata> getTracks(String releaseId) {
        // Bandcamp needs the album URL (stored in masterId), not just the id — use getTracks(ReleaseMetadata).
        log.warn("BandcampClient.getTracks(String) called without metadata; cannot resolve album URL for id={}", releaseId);
        return List.of();
    }

    @CircuitBreaker(name = "bandcampClient", fallbackMethod = "getTracksByMetadataFallback")
    @Retry(name = "bandcampClient")
    @Override
    public List<TrackMetadata> getTracks(ReleaseMetadata release) {
        log.info("Fetching tracklist from Bandcamp for release ID: {}", release.id());
        if (release.masterId() == null) {
            log.warn("Bandcamp release has no masterId (URL) — cannot fetch tracks for id={}", release.id());
            return List.of();
        }
        ReleaseMetadata withTracks = getReleaseByUrl(release.masterId());
        if (withTracks == null || withTracks.tracks() == null) return List.of();
        log.info("Found {} tracks for release {}", withTracks.tracks().size(), release.id());
        return withTracks.tracks();
    }

    public List<TrackMetadata> getTracksByMetadataFallback(ReleaseMetadata release, Exception e) {
        log.warn("Bandcamp getTracks fallback for release '{}': {}", release.id(), e.getMessage());
        return List.of();
    }

    @CircuitBreaker(name = "bandcampClient", fallbackMethod = "getReleaseByUrlFallback")
    @Retry(name = "bandcampClient")
    public ReleaseMetadata getReleaseByUrl(String url) {
        log.info("Fetching release metadata via scraper: {}", url);

        Map<String, Object> response = scraperClient.get()
                .uri(uriBuilder -> uriBuilder.path("/bandcamp/release").queryParam("url", url).build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (response == null || response.containsKey("error")) {
            throw new RuntimeException("Scraper failed to fetch release: " + url);
        }

        String artist = clean((String) response.getOrDefault("artist", "Unknown Artist"));
        String title = clean((String) response.getOrDefault("title", "Unknown Title"));

        if ("Unknown Artist".equalsIgnoreCase(artist) || "Unknown Title".equalsIgnoreCase(title)) {
            throw new RuntimeException("Scraper returned empty metadata for: " + url);
        }
        String year = (String) response.getOrDefault("year", "");
        String imageUrl = (String) response.getOrDefault("imageUrl", "");
        String type = (String) response.getOrDefault("type", "Album");
        int trackCount = ((Number) response.getOrDefault("trackCount", 0)).intValue();

        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) response.getOrDefault("tags", List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawTracks = (List<Map<String, Object>>) response.getOrDefault("tracks", List.of());
        List<TrackMetadata> tracks = rawTracks.stream()
                .map(t -> new TrackMetadata(
                        ((Number) t.getOrDefault("number", 0)).intValue(),
                        (String) t.getOrDefault("artist", artist),
                        (String) t.getOrDefault("title", "")))
                .toList();

        String releaseId = "bandcamp:" + Integer.toHexString(url.hashCode());
        log.info("Scraped metadata: {} - {} ({}), {} tracks", artist, title, year, tracks.size());

        return new ReleaseMetadata(
                releaseId, url, SearchEngine.BANDCAMP, artist, title, 80,
                year.isEmpty() ? List.of() : List.of(year),
                List.of(type), trackCount, 0, 1, tracks, imageUrl, tags, "");
    }

    public ReleaseMetadata getReleaseByUrlFallback(String url, Exception e) {
        log.warn("Bandcamp getReleaseByUrl fallback triggered for URL '{}': {}", url, e.getMessage());
        return null;
    }

    @Override
    public ReleaseMetadata getReleaseMetadata(ReleaseMetadataFile metadataFile) {
        String url = metadataFile.masterId();
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("Bandcamp URL (masterId) is missing from metadata file");
        }
        return getReleaseByUrl(url);
    }

    @Override
    public String getName() {
        return "bandcamp";
    }

    @Override
    public SearchEngine getSource() {
        return SearchEngine.BANDCAMP;
    }

    @Override
    public String buildReleaseUrl(ReleaseMetadata release) {
        return release.masterId();
    }

    private String buildSearchQuery(MetadataSearchRequest request) {
        List<String> parts = new ArrayList<>();
        if (!request.artist().isEmpty()) parts.add(request.artist());
        if (!request.release().isEmpty()) parts.add(request.release());
        else if (!request.recording().isEmpty()) parts.add(request.recording());
        return String.join(" ", parts);
    }

    private List<ReleaseMetadata> mapToDomain(List<BandcampSearchResponse.Result> results) {
        log.info("Mapping {} Bandcamp results to domain", results.size());

        Map<String, List<BandcampSearchResponse.Result>> grouped = results.stream()
                .collect(Collectors.groupingBy(r -> {
                    if (r.imageUrl() != null && !r.imageUrl().isEmpty()) return r.imageUrl();
                    return r.title().toLowerCase().trim().replaceAll("[\\p{C}\\p{Z}&&[^ ]]", "");
                }));

        log.info("Grouped into {} unique releases (by cover art)", grouped.size());

        return grouped.values().stream()
                .map(this::aggregateGroup)
                .sorted(Comparator.comparing((ReleaseMetadata m) ->
                                m.years().stream().max(String::compareTo).orElse("0000"))
                        .thenComparing(Comparator.comparingInt(ReleaseMetadata::score).reversed()))
                .toList();
    }

    private ReleaseMetadata aggregateGroup(List<BandcampSearchResponse.Result> groupResults) {
        var rep = groupResults.getFirst();
        String artist = clean(rep.artist());
        String title = clean(rep.title());

        List<String> types = groupResults.stream()
                .map(BandcampSearchResponse.Result::type).distinct()
                .map(t -> switch (t) { case "album" -> "Album"; case "track" -> "Track"; default -> t; })
                .toList();
        if (types.contains("Album")) types = types.stream().filter(t -> !"Track".equals(t)).toList();

        List<String> years = groupResults.stream()
                .map(BandcampSearchResponse.Result::year)
                .filter(y -> y != null && !y.isEmpty())
                .distinct().sorted().toList();

        List<String> tags = groupResults.stream()
                .flatMap(r -> r.tags() != null ? r.tags().stream() : java.util.stream.Stream.empty())
                .collect(Collectors.groupingBy(java.util.function.Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> e.getKey().toLowerCase()).toList();

        String releaseId = "bandcamp:" + Integer.toHexString(rep.url().hashCode());
        return new ReleaseMetadata(releaseId, rep.url(), SearchEngine.BANDCAMP, artist, title, 80,
                years, types, 0, 0, groupResults.size(), List.of(), rep.imageUrl(), tags, "");
    }

    private String clean(String text) {
        if (text == null || text.isBlank()) return text;
        return text.replaceAll("[*?\\[\\]{}|<>\"'`]", "").trim();
    }
}

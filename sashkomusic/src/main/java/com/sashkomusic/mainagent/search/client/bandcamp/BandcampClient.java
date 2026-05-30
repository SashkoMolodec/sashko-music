package com.sashkomusic.mainagent.search.client.bandcamp;

import com.sashkomusic.libraryagent.domain.model.ReleaseMetadataFile;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.search.SearchEngineService;
import com.sashkomusic.mainagent.shared.model.MetadataSearchRequest;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.mainagent.shared.model.TrackMetadata;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class BandcampClient implements SearchEngineService {

    private final RestClient scraperClient;
    private final RestClient bandcampClient;
    private final SearchContextService contextHolder;

    public BandcampClient(
            RestClient.Builder builder,
            @Lazy SearchContextService contextHolder,
            @Value("${sm.scraper.url}") String scraperUrl) {
        this.contextHolder = contextHolder;
        this.scraperClient = builder.baseUrl(scraperUrl).build();
        this.bandcampClient = builder
                .baseUrl("https://bandcamp.com")
                .defaultHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
                .build();
    }

    @CircuitBreaker(name = "bandcampClient", fallbackMethod = "searchReleasesFallback")
    @Retry(name = "bandcampClient")
    @Override
    public List<ReleaseMetadata> searchReleases(MetadataSearchRequest request) {
        String query = buildSearchQuery(request);
        log.info("Searching Bandcamp (via sm-scraper) with query: {}", query);

        List<BandcampSearchResponse.Result> results = scraperClient.get()
                .uri(uriBuilder -> uriBuilder.path("/bandcamp/search").queryParam("q", query).build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (results == null || results.isEmpty()) {
            return List.of();
        }

        log.info("Bandcamp scraper returned {} results", results.size());
        return mapToDomain(results);
    }

    public List<ReleaseMetadata> searchReleasesFallback(MetadataSearchRequest request, Exception e) {
        log.warn("Bandcamp searchReleases fallback triggered for query '{}': {}",
                buildSearchQuery(request), e.getMessage());
        return List.of();
    }

    @CircuitBreaker(name = "bandcampClient", fallbackMethod = "getTracksFallback")
    @Retry(name = "bandcampClient")
    @Override
    public List<TrackMetadata> getTracks(String releaseId) {
        log.info("Fetching tracklist from Bandcamp for release ID: {}", releaseId);

        ReleaseMetadata metadata = contextHolder.getReleaseMetadata(releaseId);
        if (metadata == null || metadata.masterId() == null) {
            log.warn("No metadata found for release ID: {}", releaseId);
            return List.of();
        }

        String url = metadata.masterId();
        log.info("Fetching tracklist from URL: {}", url);

        String html = bandcampClient.get().uri(url).retrieve().body(String.class);
        if (html == null || html.isEmpty()) {
            log.warn("Empty response from Bandcamp URL: {}", url);
            return List.of();
        }

        Document doc = Jsoup.parse(html);
        String albumArtist = metadata.artist();
        List<TrackMetadata> tracks = new ArrayList<>();
        int trackNumber = 1;

        for (Element row : doc.select("table.track_list tr.track_row_view")) {
            Element titleEl = row.selectFirst("span.track-title");
            if (titleEl == null) continue;
            String title = titleEl.text().trim();
            if (title.isEmpty()) continue;

            String trackArtist = albumArtist;
            String trackTitle = title;
            if (title.contains(" - ")) {
                int dash = title.indexOf(" - ");
                String possibleArtist = title.substring(0, dash).trim();
                String possibleTitle = title.substring(dash + 3).trim();
                if (!possibleArtist.isEmpty() && possibleArtist.length() < 100 && !possibleTitle.isEmpty()) {
                    trackArtist = possibleArtist;
                    trackTitle = possibleTitle;
                }
            }
            tracks.add(new TrackMetadata(trackNumber++, trackArtist, trackTitle));
        }

        log.info("Found {} tracks for release {}", tracks.size(), releaseId);
        return tracks;
    }

    public List<TrackMetadata> getTracksFallback(String releaseId, Exception e) {
        log.warn("Bandcamp getTracks fallback triggered for release ID '{}': {}", releaseId, e.getMessage());
        return List.of();
    }

    @CircuitBreaker(name = "bandcampClient", fallbackMethod = "getReleaseByUrlFallback")
    @Retry(name = "bandcampClient")
    public ReleaseMetadata getReleaseByUrl(String url) {
        log.info("Fetching release metadata from Bandcamp URL: {}", url);

        String html = bandcampClient.get().uri(url).retrieve().body(String.class);
        if (html == null || html.isEmpty()) {
            throw new RuntimeException("Empty response from Bandcamp URL: " + url);
        }

        Document doc = Jsoup.parse(html);
        String artist = clean(extractArtistFromPage(doc));
        String title = clean(extractTitleFromPage(doc));
        String year = extractYearFromPage(doc);
        String imageUrl = extractImageFromPage(doc);
        List<String> tags = extractTagsFromPage(doc);
        String type = extractTypeFromPage(doc);
        int trackCount = extractTrackCount(doc);
        List<TrackMetadata> tracks = extractTracksFromPage(doc, artist);

        String releaseId = "bandcamp:" + Integer.toHexString(url.hashCode());
        log.info("Extracted metadata: {} - {} ({})", artist, title, year);

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
                        .thenComparingInt(ReleaseMetadata::score).reversed())
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

    private String extractArtistFromPage(Document doc) {
        Element meta = doc.selectFirst("meta[property=og:site_name]");
        if (meta != null && !meta.attr("content").isEmpty()) return meta.attr("content");
        Element span = doc.selectFirst("span[itemprop=byArtist]");
        if (span != null) return span.text().trim();
        Element link = doc.selectFirst("p#band-name-location span.title");
        if (link != null) return link.text().trim();
        return "Unknown Artist";
    }

    private String extractTitleFromPage(Document doc) {
        Element h2 = doc.selectFirst("h2.trackTitle");
        if (h2 != null && !h2.text().trim().isEmpty()) return h2.text().trim();
        Element meta = doc.selectFirst("meta[property=og:title]");
        if (meta != null && !meta.attr("content").isEmpty()) return meta.attr("content").split(", by ")[0];
        return "Unknown Title";
    }

    private String extractYearFromPage(Document doc) {
        Element meta = doc.selectFirst("meta[itemprop=datePublished]");
        if (meta != null) {
            String date = meta.attr("content");
            if (date.length() >= 4 && date.substring(0, 4).matches("\\d{4}")) return date.substring(0, 4);
        }
        Element credits = doc.selectFirst("div.tralbum-credits");
        if (credits != null) {
            Matcher m = Pattern.compile("\\b(\\d{4})\\b").matcher(credits.text());
            if (m.find()) return m.group(1);
        }
        return "";
    }

    private String extractImageFromPage(Document doc) {
        Element meta = doc.selectFirst("meta[property=og:image]");
        if (meta != null && !meta.attr("content").isEmpty()) return meta.attr("content");
        Element img = doc.selectFirst("#tralbumArt img");
        if (img != null) return img.attr("src");
        return "";
    }

    private List<String> extractTagsFromPage(Document doc) {
        return doc.select("a.tag").stream()
                .map(e -> e.text().trim().toLowerCase())
                .filter(t -> !t.isEmpty())
                .toList();
    }

    private String extractTypeFromPage(Document doc) {
        Element trackList = doc.selectFirst("table.track_list");
        if (trackList != null && trackList.select("tr.track_row_view").size() > 1) return "Album";
        return "Track";
    }

    private int extractTrackCount(Document doc) {
        Element trackList = doc.selectFirst("table.track_list");
        return trackList != null ? trackList.select("tr.track_row_view").size() : 0;
    }

    private List<TrackMetadata> extractTracksFromPage(Document doc, String albumArtist) {
        List<TrackMetadata> tracks = new ArrayList<>();
        int n = 1;
        for (Element row : doc.select("table.track_list tr.track_row_view")) {
            Element titleEl = row.selectFirst("span.track-title");
            if (titleEl == null) continue;
            String title = titleEl.text().trim();
            if (title.isEmpty()) continue;

            String artist = albumArtist;
            String trackTitle = title;
            if (title.contains(" - ")) {
                int dash = title.indexOf(" - ");
                String a = title.substring(0, dash).trim();
                String t = title.substring(dash + 3).trim();
                if (!a.isEmpty() && a.length() < 100 && !t.isEmpty()) { artist = a; trackTitle = t; }
            }
            tracks.add(new TrackMetadata(n++, artist, trackTitle));
        }
        return tracks;
    }

    private String clean(String text) {
        if (text == null || text.isBlank()) return text;
        return text.replaceAll("[*?\\[\\]{}|<>\"'`]", "").trim();
    }
}

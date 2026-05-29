package com.sashkomusic.mainagent.search.client.bandcamp;

import com.sashkomusic.mainagent.shared.model.MetadataSearchRequest;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.libraryagent.domain.model.ReleaseMetadataFile;
import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.shared.model.TrackMetadata;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.SearchEngineService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class BandcampClient implements SearchEngineService {

    private final RestClient client;
    private final SearchContextService contextHolder;

    public BandcampClient(RestClient.Builder builder, @Lazy SearchContextService contextHolder) {
        this.contextHolder = contextHolder;
        this.client = builder
                .baseUrl("https://bandcamp.com")
                .defaultHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .build();
    }

    @CircuitBreaker(name = "bandcampClient", fallbackMethod = "searchReleasesFallback")
    @Retry(name = "bandcampClient")
    @Override
    public List<ReleaseMetadata> searchReleases(MetadataSearchRequest request) {
        String query = buildSearchQuery(request);
        log.info("Searching Bandcamp with query: {}", query);

        try {
            // Fetch first 3 pages of results
            List<BandcampSearchResponse.Result> allResults = new ArrayList<>();

            for (int page = 1; page <= 3; page++) {
                final int currentPage = page; // Make effectively final for lambda
                log.info("Fetching Bandcamp page {}", currentPage);

                String html = client.get()
                        .uri(uriBuilder -> {
                            var builder = uriBuilder.path("/search").queryParam("q", query);
                            if (currentPage > 1) {
                                builder.queryParam("page", currentPage);
                            }
                            return builder.build();
                        })
                        .retrieve()
                        .body(String.class);

                if (html == null || html.isEmpty()) {
                    break;
                }

                List<BandcampSearchResponse.Result> pageResults = parseSearchResults(html);
                if (pageResults.isEmpty()) {
                    log.info("No results on page {}, stopping pagination", page);
                    break;
                }

                allResults.addAll(pageResults);
                log.info("Page {} added {} results (total: {})", page, pageResults.size(), allResults.size());

                // Check if there's a next page
                if (!hasNextPage(html)) {
                    log.info("No more pages available, stopping at page {}", page);
                    break;
                }
            }

            if (allResults.isEmpty()) {
                return List.of();
            }

            log.info("Total results from all pages: {}", allResults.size());
            return mapToDomain(allResults);

        } catch (Exception ex) {
            log.error("Error searching Bandcamp: {}", ex.getMessage());
            throw ex;
        }
    }

    public List<ReleaseMetadata> searchReleasesFallback(MetadataSearchRequest request, Exception e) {
        log.warn("Bandcamp searchReleases fallback triggered for query '{}': {}",
            buildSearchQuery(request), e.getMessage());
        return List.of();
    }

    private String buildSearchQuery(MetadataSearchRequest request) {
        List<String> parts = new ArrayList<>();

        if (!request.artist().isEmpty()) {
            parts.add(request.artist());
        }
        if (!request.release().isEmpty()) {
            parts.add(request.release());
        } else if (!request.recording().isEmpty()) {
            parts.add(request.recording());
        }

        return String.join(" ", parts);
    }

    private List<BandcampSearchResponse.Result> parseSearchResults(String html) {
        List<BandcampSearchResponse.Result> results = new ArrayList<>();

        try {
            Document doc = Jsoup.parse(html);

            // Bandcamp search results are in <li class="searchresult">
            var resultElements = doc.select("li.searchresult");

            log.info("Found {} search result elements", resultElements.size());

            for (Element element : resultElements) {
                try {
                    // Extract data from each result
                    String type = extractType(element);

                    // Skip non-album/track results
                    if (!type.equals("album") && !type.equals("track")) {
                        continue;
                    }

                    String artist = extractArtist(element);
                    String title = extractTitle(element);
                    String url = extractUrl(element);
                    String imageUrl = extractImageUrl(element);
                    String year = extractYear(element);
                    List<String> tags = extractTags(element);

                    if (!title.isEmpty() && !url.isEmpty()) {
                        results.add(new BandcampSearchResponse.Result(
                                artist,
                                title,
                                type,
                                url,
                                imageUrl,
                                year,
                                tags
                        ));
                    }
                } catch (Exception ex) {
                    log.warn("Error parsing search result element: {}", ex.getMessage());
                }
            }

        } catch (Exception ex) {
            log.error("Error parsing HTML: {}", ex.getMessage());
        }

        return results;
    }

    private String extractType(Element element) {
        // Type is in <div class="itemtype">ALBUM</div> or similar
        Element typeElement = element.selectFirst("div.itemtype");
        if (typeElement != null) {
            return typeElement.text().toLowerCase().trim();
        }
        return "unknown";
    }

    private String extractArtist(Element element) {
        // Artist is in <div class="subhead">by Artist Name</div>
        Element artistElement = element.selectFirst("div.subhead");
        if (artistElement != null) {
            String text = artistElement.text();
            // Remove "by " prefix if present
            if (text.startsWith("by ")) {
                return text.substring(3).trim();
            }
            return text.trim();
        }
        return "Unknown Artist";
    }

    private String extractTitle(Element element) {
        // Title is in <div class="heading"><a>Title</a></div>
        Element titleElement = element.selectFirst("div.heading a");
        if (titleElement != null) {
            return titleElement.text().trim();
        }
        return "";
    }

    private String extractUrl(Element element) {
        // URL is in <div class="heading"><a href="...">
        Element linkElement = element.selectFirst("div.heading a");
        if (linkElement != null) {
            return linkElement.attr("href");
        }
        return "";
    }

    private String extractImageUrl(Element element) {
        // Image is in <img src="...">
        Element imgElement = element.selectFirst("img");
        if (imgElement != null) {
            String imageUrl = imgElement.attr("src");
            // Upgrade image quality: _7 -> _16 for better resolution
            return imageUrl.replace("_7.jpg", "_16.jpg");
        }
        return "";
    }

    private String extractYear(Element element) {
        // Year is in <div class="released">released October 31, 2025</div>
        Element releasedElement = element.selectFirst("div.released");
        if (releasedElement != null) {
            String text = releasedElement.text(); // "released October 31, 2025"
            // Extract the last 4 digits (year)
            String[] parts = text.split("\\s+");
            for (int i = parts.length - 1; i >= 0; i--) {
                String part = parts[i].trim();
                if (part.matches("\\d{4}")) {
                    return part;
                }
            }
        }
        return "";
    }

    private List<String> extractTags(Element element) {
        // Tags are in <div class="tags">
        Element tagsElement = element.selectFirst("div.tags");
        if (tagsElement != null) {
            // Try to get individual tag links first
            var tagLinks = tagsElement.select("a");
            if (!tagLinks.isEmpty()) {
                return tagLinks.stream()
                        .map(Element::text)
                        .filter(t -> !t.isEmpty())
                        .filter(t -> !t.endsWith(":")) // Filter out labels like "tags:"
                        .toList();
            }

            // Fallback: parse text and split by comma or whitespace
            String tagsText = tagsElement.text().trim();
            if (!tagsText.isEmpty()) {
                return List.of(tagsText.split("[,\\s]+"))
                        .stream()
                        .map(String::trim)
                        .filter(t -> !t.isEmpty())
                        .filter(t -> !t.endsWith(":")) // Filter out labels like "tags:"
                        .toList();
            }
        }
        return List.of();
    }

    private boolean hasNextPage(String html) {
        try {
            Document doc = Jsoup.parse(html);
            // Check for "next" link in pagination
            Element nextLink = doc.selectFirst("a.nextprev.next");
            return nextLink != null;
        } catch (Exception ex) {
            log.warn("Error checking for next page: {}", ex.getMessage());
            return false;
        }
    }

    private List<ReleaseMetadata> mapToDomain(List<BandcampSearchResponse.Result> results) {
        log.info("Mapping {} Bandcamp results to domain", results.size());

        // Group by cover URL (same album/release = same cover art)
        // Fall back to normalized title if no cover URL
        Map<String, List<BandcampSearchResponse.Result>> grouped = results.stream()
                .collect(Collectors.groupingBy(r -> {
                    if (r.imageUrl() != null && !r.imageUrl().isEmpty()) {
                        return r.imageUrl();
                    }
                    // Fallback to title if no image
                    String title = r.title().toLowerCase().trim();
                    return title.replaceAll("[\\p{C}\\p{Z}&&[^ ]]", "");
                }));

        log.info("Grouped into {} unique releases (by cover art)", grouped.size());

        return grouped.values().stream()
                .map(this::aggregateGroup)
                .sorted(Comparator.comparing((ReleaseMetadata m) -> {
                    // Get the maximum (newest) year from the group
                    return m.years().stream()
                            .max(String::compareTo)
                            .orElse("0000");
                }).thenComparingInt(ReleaseMetadata::score).reversed())
                .toList();
    }

    private ReleaseMetadata aggregateGroup(List<BandcampSearchResponse.Result> groupResults) {
        var representative = groupResults.getFirst();

        String artist = representative.artist();
        String title = representative.title();
        String url = representative.url();
        String imageUrl = representative.imageUrl();

        // Clean special characters that break file search
        artist = clean(artist);
        title = clean(title);

        // Collect types from all results in group
        List<String> types = groupResults.stream()
                .map(BandcampSearchResponse.Result::type)
                .distinct()
                .map(type -> switch (type) {
                    case "album" -> "Album";
                    case "track" -> "Track";
                    default -> type;
                })
                .toList();

        // Filter out "Track" if "Album" is present (album is more comprehensive)
        if (types.contains("Album")) {
            types = types.stream()
                    .filter(t -> !"Track".equals(t))
                    .toList();
        }

        // Collect years from all results in group
        List<String> years = groupResults.stream()
                .map(BandcampSearchResponse.Result::year)
                .filter(y -> y != null && !y.isEmpty())
                .distinct()
                .sorted()
                .toList();

        // Aggregate tags by frequency (most common first)
        List<String> tags = groupResults.stream()
                .flatMap(r -> r.tags() != null ? r.tags().stream() : java.util.stream.Stream.empty())
                .collect(Collectors.groupingBy(
                        java.util.function.Function.identity(),
                        Collectors.counting()
                ))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> e.getKey().toLowerCase())
                .toList(); // Keep all tags, display will be limited

        // Create shorter release ID using URL hash to stay under Telegram's 64-byte limit
        // Format: "bandcamp:hash" where hash is a short hex string
        String releaseId = "bandcamp:" + Integer.toHexString(url.hashCode());

        return new ReleaseMetadata(
                releaseId,
                url, // Store full URL in masterId field for later retrieval
                SearchEngine.BANDCAMP,
                artist,
                title,
                80, // Default score for Bandcamp results (lower than Discogs/MusicBrainz)
                years,
                types,
                0, // Track count not available from search
                0,
                groupResults.size(),
                List.of(),
                imageUrl,
                tags,
                "" // Label not available from Bandcamp
        );
    }

    /**
     * Get release metadata by URL.
     * Used for reprocessing - fetches fresh metadata from a known Bandcamp URL.
     */
    @CircuitBreaker(name = "bandcampClient", fallbackMethod = "getReleaseByUrlFallback")
    @Retry(name = "bandcampClient")
    public ReleaseMetadata getReleaseByUrl(String url) {
        log.info("Fetching release metadata from Bandcamp URL: {}", url);

        try {
            // Fetch release page HTML
            String html = client.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);

            if (html == null || html.isEmpty()) {
                throw new RuntimeException("Empty response from Bandcamp URL: " + url);
            }

            Document doc = Jsoup.parse(html);

            // Extract metadata from page
            String artist = extractArtistFromPage(doc);
            String title = extractTitleFromPage(doc);
            String year = extractYearFromPage(doc);
            String imageUrl = extractImageFromPage(doc);
            List<String> tags = extractTagsFromPage(doc);
            String type = extractTypeFromPage(doc);
            int trackCount = extractTrackCount(doc);

            // Clean special characters
            artist = clean(artist);
            title = clean(title);

            // Extract tracks from page
            List<TrackMetadata> tracks = extractTracksFromPage(doc, artist);

            // Create release ID using URL hash
            String releaseId = "bandcamp:" + Integer.toHexString(url.hashCode());

            log.info("Extracted metadata: {} - {} ({})", artist, title, year);

            return new ReleaseMetadata(
                    releaseId,
                    url, // Store full URL in masterId
                    SearchEngine.BANDCAMP,
                    artist,
                    title,
                    80,
                    year.isEmpty() ? List.of() : List.of(year),
                    List.of(type),
                    trackCount,
                    0,
                    1,
                    tracks,
                    imageUrl,
                    tags,
                    ""
            );

        } catch (Exception ex) {
            log.error("Error fetching release from Bandcamp URL {}: {}", url, ex.getMessage());
            throw new RuntimeException("Failed to fetch Bandcamp release: " + ex.getMessage(), ex);
        }
    }

    public ReleaseMetadata getReleaseByUrlFallback(String url, Exception e) {
        log.warn("Bandcamp getReleaseByUrl fallback triggered for URL '{}': {}",
            url, e.getMessage());
        return null;
    }

    private String extractArtistFromPage(Document doc) {
        // Try meta tag first
        Element metaArtist = doc.selectFirst("meta[property=og:site_name]");
        if (metaArtist != null) {
            String artist = metaArtist.attr("content");
            if (!artist.isEmpty()) {
                return artist;
            }
        }

        // Try span with itemprop
        Element artistSpan = doc.selectFirst("span[itemprop=byArtist]");
        if (artistSpan != null) {
            return artistSpan.text().trim();
        }

        // Try band name link
        Element bandLink = doc.selectFirst("p#band-name-location span.title");
        if (bandLink != null) {
            return bandLink.text().trim();
        }

        return "Unknown Artist";
    }

    private String extractTitleFromPage(Document doc) {
        // Try h2.trackTitle first for a clean title
        Element titleElement = doc.selectFirst("h2.trackTitle");
        if (titleElement != null && !titleElement.text().trim().isEmpty()) {
            return titleElement.text().trim();
        }

        // Fallback to meta tag and clean it
        Element metaTitle = doc.selectFirst("meta[property=og:title]");
        if (metaTitle != null) {
            String title = metaTitle.attr("content");
            if (!title.isEmpty()) {
                // handle "Title, by Artist"
                return title.split(", by ")[0];
            }
        }

        return "Unknown Title";
    }

    private String extractYearFromPage(Document doc) {
        // Try meta datePublished
        Element metaDate = doc.selectFirst("meta[itemprop=datePublished]");
        if (metaDate != null) {
            String datePublished = metaDate.attr("content"); // Format: "20231031"
            if (datePublished != null && datePublished.length() >= 4) {
                String year = datePublished.substring(0, 4);
                if (year.matches("\\d{4}")) {
                    log.debug("Extracted year {} from meta[itemprop=datePublished]", year);
                    return year;
                }
            }
        }

        // Try .tralbumData script (JSON)
        Element tralbumScript = doc.selectFirst("script[data-tralbum]");
        if (tralbumScript != null) {
            String json = tralbumScript.attr("data-tralbum");
            if (json != null && json.contains("\"release_date\"")) {
                try {
                    int startIdx = json.indexOf("\"release_date\":") + 15;
                    String dateStr = json.substring(startIdx, startIdx + 25).split("\"")[0];
                    java.util.regex.Pattern yearPattern = java.util.regex.Pattern.compile("\\b(\\d{4})\\b");
                    java.util.regex.Matcher matcher = yearPattern.matcher(dateStr);
                    if (matcher.find()) {
                        String year = matcher.group(1);
                        log.debug("Extracted year {} from script[data-tralbum]", year);
                        return year;
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse release_date from JSON", e);
                }
            }
        }
        
        // Fallback to .tralbum-credits div
        Element creditsElement = doc.selectFirst("div.tralbum-credits");
        if (creditsElement != null) {
            String text = creditsElement.text();
            // Look for a 4-digit number, probably the year
            java.util.regex.Pattern yearPattern = java.util.regex.Pattern.compile("\\b(\\d{4})\\b");
            java.util.regex.Matcher matcher = yearPattern.matcher(text);
            if (matcher.find()) {
                String year = matcher.group(1);
                log.debug("Extracted year {} from div.tralbum-credits", year);
                return year;
            }
        }

        log.warn("Could not extract year from Bandcamp page");
        return "";
    }

    private String extractImageFromPage(Document doc) {
        // Try meta og:image
        Element metaImage = doc.selectFirst("meta[property=og:image]");
        if (metaImage != null) {
            String imageUrl = metaImage.attr("content");
            if (!imageUrl.isEmpty()) {
                return imageUrl;
            }
        }

        // Try #tralbumArt img
        Element artImg = doc.selectFirst("#tralbumArt img");
        if (artImg != null) {
            return artImg.attr("src");
        }

        return "";
    }

    private List<String> extractTagsFromPage(Document doc) {
        List<String> tags = new ArrayList<>();

        // Tags are in .tralbumData script or in tags section
        var tagLinks = doc.select("a.tag");
        for (Element tagLink : tagLinks) {
            String tag = tagLink.text().trim().toLowerCase();
            if (!tag.isEmpty()) {
                tags.add(tag);
            }
        }

        return tags;
    }

    private String extractTypeFromPage(Document doc) {
        // Check if it's an album or track
        Element trackList = doc.selectFirst("table.track_list");
        if (trackList != null) {
            var trackRows = trackList.select("tr.track_row_view");
            if (trackRows.size() > 1) {
                return "Album";
            }
        }
        return "Track";
    }

    private int extractTrackCount(Document doc) {
        Element trackList = doc.selectFirst("table.track_list");
        if (trackList != null) {
            var trackRows = trackList.select("tr.track_row_view");
            return trackRows.size();
        }
        return 0;
    }

    private List<TrackMetadata> extractTracksFromPage(Document doc, String albumArtist) {
        var trackRows = doc.select("table.track_list tr.track_row_view");
        log.info("Found {} track rows in Bandcamp page", trackRows.size());

        List<TrackMetadata> tracks = new ArrayList<>();
        int trackNumber = 1;

        for (Element row : trackRows) {
            // Try more specific selector first
            Element titleElement = row.selectFirst("td.title-col div.title span.track-title");
            if (titleElement == null) {
                // Fallback to simpler selector
                titleElement = row.selectFirst("span.track-title");
                log.debug("Using fallback selector for track {}", trackNumber);
            }
            if (titleElement != null) {
                String title = titleElement.text().trim();
                log.info("Extracted track {} title: '{}'", trackNumber, title);
                if (!title.isEmpty()) {
                    // Try to parse per-track artist from title if format is "Artist - Title"
                    String trackArtist = albumArtist;
                    String trackTitle = title;

                    if (title.contains(" - ")) {
                        int dashIndex = title.indexOf(" - ");
                        String possibleArtist = title.substring(0, dashIndex).trim();
                        String possibleTitle = title.substring(dashIndex + 3).trim();

                        // Only split if the artist part looks reasonable
                        if (!possibleArtist.isEmpty() && possibleArtist.length() < 100 && !possibleTitle.isEmpty()) {
                            trackArtist = possibleArtist;
                            trackTitle = possibleTitle;
                        }
                    }

                    tracks.add(new TrackMetadata(trackNumber, trackArtist, trackTitle));
                    trackNumber++;
                }
            }
        }

        return tracks;
    }

    @CircuitBreaker(name = "bandcampClient", fallbackMethod = "getTracksFallback")
    @Retry(name = "bandcampClient")
    @Override
    public List<TrackMetadata> getTracks(String releaseId) {
        log.info("Fetching tracklist from Bandcamp for release ID: {}", releaseId);

        try {
            ReleaseMetadata metadata = contextHolder.getReleaseMetadata(releaseId);
            if (metadata == null || metadata.masterId() == null) {
                log.warn("No metadata found for release ID: {}", releaseId);
                return List.of();
            }

            String url = metadata.masterId(); // Full Bandcamp URL stored here
            log.info("Fetching tracklist from URL: {}", url);

            // Fetch release page HTML
            String html = client.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);

            if (html == null || html.isEmpty()) {
                log.warn("Empty response from Bandcamp URL: {}", url);
                return List.of();
            }

            // Parse track list from table
            Document doc = Jsoup.parse(html);
            var trackRows = doc.select("table.track_list tr.track_row_view");

            // Get album artist from metadata
            String albumArtist = metadata.artist();

            List<TrackMetadata> tracks = new ArrayList<>();
            int trackNumber = 1;

            for (Element row : trackRows) {
                Element titleElement = row.selectFirst("span.track-title");
                if (titleElement != null) {
                    String title = titleElement.text().trim();
                    if (!title.isEmpty()) {
                        // Try to parse per-track artist from title if format is "Artist - Title"
                        String trackArtist = albumArtist;
                        String trackTitle = title;

                        if (title.contains(" - ")) {
                            int dashIndex = title.indexOf(" - ");
                            String possibleArtist = title.substring(0, dashIndex).trim();
                            String possibleTitle = title.substring(dashIndex + 3).trim();

                            // Only split if the artist part looks reasonable (not empty, not too long)
                            if (!possibleArtist.isEmpty() && possibleArtist.length() < 100 && !possibleTitle.isEmpty()) {
                                trackArtist = possibleArtist;
                                trackTitle = possibleTitle;
                                log.debug("Parsed track artist from title: '{}' - '{}'", trackArtist, trackTitle);
                            }
                        }

                        tracks.add(new TrackMetadata(trackNumber, trackArtist, trackTitle));
                        trackNumber++;
                    }
                }
            }

            log.info("Found {} tracks for release {}", tracks.size(), releaseId);
            return tracks;

        } catch (Exception ex) {
            log.error("Error fetching tracklist from Bandcamp: {}", ex.getMessage());
            throw ex;
        }
    }

    public List<TrackMetadata> getTracksFallback(String releaseId, Exception e) {
        log.warn("Bandcamp getTracks fallback triggered for release ID '{}': {}",
            releaseId, e.getMessage());
        return List.of();
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
        // Bandcamp URL is stored in masterId field
        return release.masterId();
    }

    @Override
    public ReleaseMetadata getReleaseMetadata(ReleaseMetadataFile metadataFile) {
        log.info("Refreshing metadata from Bandcamp for: {} - {}",
                metadataFile.artist(), metadataFile.title());

        // For Bandcamp, masterId contains the full URL to the release page
        String url = metadataFile.masterId();
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("Bandcamp URL (masterId) is missing from metadata file");
        }

        return getReleaseByUrl(url);
    }

    private String clean(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        // Remove special characters that break file search
        return text.replaceAll("[*?\\[\\]{}|<>\"'`]", "").trim();
    }
}

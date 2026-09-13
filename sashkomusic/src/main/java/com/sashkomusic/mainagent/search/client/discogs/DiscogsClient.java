package com.sashkomusic.mainagent.search.client.discogs;

import com.sashkomusic.shared.model.MetadataSearchRequest;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.libraryagent.domain.model.ReleaseMetadataFile;
import com.sashkomusic.shared.model.SearchEngine;
import com.sashkomusic.shared.model.TrackMetadata;
import com.sashkomusic.mainagent.search.SearchEngineService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DiscogsClient implements SearchEngineService {

    private final RestClient client;
    private final String apiToken;
    private final DiscogsClient self;

    public DiscogsClient(RestClient.Builder builder, @Value("${discogs.api.token:}") String apiToken, @Lazy DiscogsClient self) {
        this.apiToken = apiToken;
        this.client = builder
                .baseUrl("https://api.discogs.com")
                .defaultHeader("User-Agent", "SashkoMusicBot/1.0")
                .build();
        this.self = self;
    }

    @Override
    public List<ReleaseMetadata> searchReleases(MetadataSearchRequest request) {
        log.info("Searching Discogs with parameters: artist={}, release={}, year={}, format={}, style={}, label={}, country={}",
                request.artist(), request.release(),
                request.dateRange() != null ? request.dateRange().toDiscogsParam() : "",
                request.format(), request.style(), request.label(), request.country());

        List<ReleaseMetadata> results = self.performSearch(request);

        if (results.isEmpty() && !request.artist().isEmpty()) {
            results = retryWithoutArtist(request);
        }

        return results;
    }

    private List<ReleaseMetadata> retryWithoutArtist(MetadataSearchRequest request) {
        List<ReleaseMetadata> results;
        log.info("No results found with artist='{}'. Retrying without artist.", request.artist());
        MetadataSearchRequest requestWithoutArtist = new MetadataSearchRequest(
                request.id(),
                "",
                request.release(),
                request.recording(),
                request.dateRange(),
                request.format(),
                request.type(),
                request.country(),
                request.status(),
                request.style(),
                request.label(),
                request.catno()
        );
        results = self.performSearch(requestWithoutArtist);
        return results;
    }

    @CircuitBreaker(name = "discogsClient", fallbackMethod = "performSearchFallback")
    @Retry(name = "discogsClient")
    protected List<ReleaseMetadata> performSearch(MetadataSearchRequest request) {
        try {
            var response = client.get()
                    .uri(uriBuilder -> {
                        addDiscogsParameters(uriBuilder, request);
                        var uri = uriBuilder.build();
                        log.info("Discogs request URL: {}", uri);
                        return uri;
                    })
                    .retrieve()
                    .body(DiscogsSearchResponse.class);

            if (response == null || response.results() == null || response.results().isEmpty()) {
                return List.of();
            }

            return mapToDomain(response.results(), request);

        } catch (Exception ex) {
            log.error("Error searching Discogs: {}", ex.getMessage());
            throw ex;
        }
    }

    private List<ReleaseMetadata> performSearchFallback(MetadataSearchRequest request, Exception e) {
        log.warn("Discogs performSearch fallback triggered for artist '{}', release '{}': {}",
            request.artist(), request.release(), e.getMessage());
        return List.of();
    }

    /**
     * Uses Discogs' structured search filters (artist, release_title, style, genre, year, label,
     * country, format, catno) instead of concatenating everything into the free-text 'q' param.
     * 'q' is relevance-ranked full-text search across titles — cramming "trance 1994" into it
     * matches releases with "trance" literally in the title, not releases tagged trance from 1994.
     * Falls back to 'q' only when the extractor found nothing structured (e.g. a bare phrase).
     */
    private void addDiscogsParameters(UriBuilder builder, MetadataSearchRequest request) {
        builder.path("/database/search")
                .queryParam("type", "release")
                .queryParam("per_page", "200");

        boolean hasRelease = !request.release().isEmpty();
        boolean hasRecording = !request.recording().isEmpty();
        boolean anyStructuredField = !request.artist().isEmpty() || hasRelease || hasRecording
                || (request.dateRange() != null && !request.dateRange().isEmpty())
                || !request.format().isEmpty() || !request.catno().isEmpty() || !request.label().isEmpty()
                || !request.style().isEmpty() || !request.country().isEmpty();

        if (!request.artist().isEmpty()) {
            builder.queryParam("artist", request.artist());
        }
        if (hasRelease) {
            builder.queryParam("release_title", request.release());
        } else if (hasRecording) {
            builder.queryParam("track", request.recording());
        }
        if (request.dateRange() != null && !request.dateRange().isEmpty()) {
            builder.queryParam("year", request.dateRange().toDiscogsParam());
        }
        if (!request.format().isEmpty()) {
            builder.queryParam("format", request.format());
        }
        if (!request.catno().isEmpty()) {
            builder.queryParam("catno", request.catno());
        }
        if (!request.label().isEmpty()) {
            builder.queryParam("label", request.label());
        }
        if (!request.style().isEmpty()) {
            // Extractor's "style" field holds genre-name-like terms (e.g. "trance", "idm") which
            // map onto Discogs' fine-grained `style` taxonomy (genre-vs-style split per Discogs'
            // own guidelines) — not the broader `genre` param.
            builder.queryParam("style", request.style());
        }
        if (!request.country().isEmpty()) {
            builder.queryParam("country", request.country());
        }

        if (!anyStructuredField) {
            log.info("No structured fields extracted — leaving search unconstrained (type=release only)");
        }

        if (!apiToken.isEmpty()) {
            builder.queryParam("token", apiToken);
        }
    }

    private List<ReleaseMetadata> mapToDomain(List<DiscogsSearchResponse.Result> results, MetadataSearchRequest request) {
        log.debug("Mapping {} Discogs results to domain", results.size());

        List<DiscogsSearchResponse.Result> releases = results.stream()
                .filter(r -> "release".equals(r.type()))
                .toList();

        log.debug("After filtering for 'release' type, {} releases remain", releases.size());

        releases = filterByRequestedArtist(releases, request.artist());

        log.debug("After filtering for requested artist '{}', {} releases remain", request.artist(), releases.size());

        // Group by ARTIST + TITLE, not title alone — a title-only key merges unrelated releases
        // that happen to share a generic title (e.g. "Imaginary Landscapes" is both a well-known
        // John Cage piece with a dozen reissues AND an unrelated electronic release — grouping by
        // title alone merged all of them into one release with mashed-together years/tags/label).
        // LinkedHashMap preserves Discogs' relevance ordering (order of first appearance in the
        // response) across the grouping — a plain groupingBy() uses a HashMap and would scramble it.
        Map<String, List<DiscogsSearchResponse.Result>> grouped = releases.stream()
                .collect(Collectors.groupingBy(r -> {
                    String artist = extractArtist(r.title()).toLowerCase().trim();
                    String title = extractTitle(r.title()).toLowerCase().trim();
                    return (artist + "::" + title).replaceAll("[\\p{C}\\p{Z}&&[^ ]]", "");
                }, LinkedHashMap::new, Collectors.toList()));

        log.debug("Grouped into {} unique titles", grouped.size());

        // No re-sort: Discogs already returns results ordered by relevance, and every group's
        // score is currently a flat constant (see aggregateGroup) so a numeric re-sort is a no-op
        // that only serves to destroy that relevance order.
        return grouped.values().stream()
                .map(this::aggregateGroup)
                .toList();
    }

    private ReleaseMetadata aggregateGroup(List<DiscogsSearchResponse.Result> groupResults) {
        var representative = groupResults.getFirst();

        String artist = extractArtist(representative.title());
        String title = extractTitle(representative.title());

        artist = cleanArtistName(clean(artist));
        title = clean(title);

        List<String> years = groupResults.stream()
                .map(DiscogsSearchResponse.Result::year)
                .filter(Objects::nonNull)
                .filter(y -> !y.isEmpty())
                .distinct()
                .sorted()
                .toList();

        List<String> types = groupResults.stream()
                .map(DiscogsSearchResponse.Result::format)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .distinct()
                .toList();

        // Combine genre and style for comprehensive tags
        List<String> tags = groupResults.stream()
                .flatMap(r -> {
                    java.util.stream.Stream<String> genreStream = r.genre() != null ? r.genre().stream() : java.util.stream.Stream.empty();
                    java.util.stream.Stream<String> styleStream = r.style() != null ? r.style().stream() : java.util.stream.Stream.empty();
                    return java.util.stream.Stream.concat(genreStream, styleStream);
                })
                .collect(Collectors.groupingBy(
                        java.util.function.Function.identity(),
                        Collectors.counting()
                ))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(7)
                .map(e -> e.getKey().toLowerCase())
                .toList();

        String releaseId = "discogs:release:" + representative.id();

        // masterId for grouping - only set if release has a master
        String masterId = (representative.masterId() != null && representative.masterId() != 0) ?
                String.valueOf(representative.masterId()) :
                null;

        // Extract label from Discogs label list
        String label = groupResults.stream()
                .filter(r -> r.label() != null && !r.label().isEmpty())
                .flatMap(r -> r.label().stream())
                .findFirst()
                .map(this::cleanLabelName)
                .orElse("");

        return new ReleaseMetadata(
                releaseId,
                masterId,
                SearchEngine.DISCOGS,
                artist,
                title,
                100, // Default score for Discogs results
                years,
                types,
                0, // Track count not available in search response
                0,
                groupResults.size(),
                List.of(),
                representative.coverImage(), // Use Discogs cover image URL
                tags,
                label
        );
    }

    // Discogs' `artist=` search param is a relevance hint over its full-text index, not an exact
    // filter — it happily returns releases from other artist entities that merely share the
    // literal name (Discogs disambiguates same-name artists as "Alpi", "Alpi (2)", "Alpi (3)", ...
    // and the API ranks all of them). Nothing upstream verifies the returned artist actually
    // matches what was requested, so narrow it down here. If narrowing empties the list, trust
    // Discogs' own ranking rather than showing nothing — better a loose result than none.
    private List<DiscogsSearchResponse.Result> filterByRequestedArtist(List<DiscogsSearchResponse.Result> releases, String requestedArtist) {
        if (requestedArtist == null || requestedArtist.isBlank()) {
            return releases;
        }
        String normalizedRequested = requestedArtist.toLowerCase().trim();
        List<DiscogsSearchResponse.Result> matches = releases.stream()
                .filter(r -> extractArtist(r.title()).toLowerCase().trim().equals(normalizedRequested))
                .toList();
        return matches.isEmpty() ? releases : matches;
    }

    private String extractArtist(String fullTitle) {
        if (fullTitle == null) return "Unknown Artist";
        int dashIndex = fullTitle.indexOf(" - ");
        if (dashIndex > 0) {
            return fullTitle.substring(0, dashIndex).trim();
        }
        return "Unknown Artist";
    }

    private String extractTitle(String fullTitle) {
        if (fullTitle == null) return "Unknown";
        int dashIndex = fullTitle.indexOf(" - ");
        if (dashIndex > 0 && dashIndex + 3 < fullTitle.length()) {
            return fullTitle.substring(dashIndex + 3).trim();
        }
        return fullTitle;
    }

    private String clean(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return text.replaceAll("[*?\\[\\]{}|<>\"'`]", "").trim();
    }

    @CircuitBreaker(name = "discogsClient", fallbackMethod = "getReleaseByIdFallback")
    @Retry(name = "discogsClient")
    public ReleaseMetadata getReleaseById(String releaseId) {
        log.info("Fetching release metadata from Discogs for ID: {}", releaseId);

        // Parse releaseId format: "discogs:master:123" or "discogs:release:456"
        if (!releaseId.startsWith("discogs:")) {
            log.warn("Invalid Discogs release ID format: {}", releaseId);
            return null;
        }

        String[] parts = releaseId.split(":");
        if (parts.length != 3) {
            log.warn("Invalid Discogs release ID format: {}", releaseId);
            return null;
        }

        String type = parts[1]; // "master" or "release"
        String id = parts[2];   // actual ID

        try {
            DiscogsReleaseResponse response;
            response = client.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/releases/" + id);
                        if (!apiToken.isEmpty()) {
                            uriBuilder.queryParam("token", apiToken);
                        }
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .body(DiscogsReleaseResponse.class);

            if (response == null) {
                log.warn("Release not found: {}", releaseId);
                return null;
            }

            String artist = response.artists() != null && !response.artists().isEmpty()
                    ? cleanArtistName(response.artists().getFirst().name())
                    : "Unknown";
            String title = response.title() != null ? clean(response.title()) : "Unknown";

            List<TrackMetadata> tracks = getTracks(releaseId);

            List<String> years = response.year() != null
                    ? List.of(String.valueOf(response.year()))
                    : List.of();

            List<String> types = new java.util.ArrayList<>();
            if (response.formats() != null && !response.formats().isEmpty()) {
                response.formats().forEach(format -> {
                    if (format.name() != null) {
                        types.add(format.name());
                    }
                    if (format.descriptions() != null) {
                        types.addAll(format.descriptions());
                    }
                });
            }

            // Combine genres and styles for tags
            List<String> tags = new java.util.ArrayList<>();
            if (response.genres() != null) {
                tags.addAll(response.genres().stream().map(String::toLowerCase).toList());
            }
            if (response.styles() != null) {
                tags.addAll(response.styles().stream().map(String::toLowerCase).toList());
            }

            String label = "";
            if (response.labels() != null && !response.labels().isEmpty()) {
                DiscogsReleaseResponse.Label firstLabel = response.labels().getFirst();
                label = firstLabel != null && firstLabel.name() != null ? cleanLabelName(firstLabel.name()) : "";
            }

            String coverUrl = null;
            if (response.images() != null && !response.images().isEmpty()) {
                var primaryImage = response.images().stream()
                        .filter(img -> img != null && "primary".equals(img.type()))
                        .findFirst();

                if (primaryImage.isPresent()) {
                    coverUrl = primaryImage.get().uri();
                } else if (!response.images().isEmpty()) {
                    DiscogsReleaseResponse.Image firstImage = response.images().getFirst();
                    coverUrl = firstImage != null ? firstImage.uri() : null;
                }
            }

            return new ReleaseMetadata(
                    releaseId,
                    id,
                    SearchEngine.DISCOGS,
                    artist,
                    title,
                    100,
                    years,
                    types,
                    tracks.size(),
                    tracks.size(),
                    1,
                    tracks,
                    coverUrl,
                    tags,
                    label
            );

        } catch (Exception ex) {
            log.error("Error fetching release metadata from Discogs (will retry): {}", ex.getMessage());
            throw ex;
        }
    }

    public ReleaseMetadata getReleaseByIdFallback(String releaseId, Exception e) {
        log.warn("Discogs getReleaseById fallback triggered for release ID '{}': {}",
            releaseId, e.getMessage());
        return null;
    }

    /**
     * First community-curated YouTube link on the Discogs release page, if any. This is free
     * (no extra scraping) and usually points at the exact pressing rather than a generic search.
     */
    @CircuitBreaker(name = "discogsClient", fallbackMethod = "getPrimaryVideoUrlFallback")
    @Retry(name = "discogsClient")
    public Optional<String> getPrimaryVideoUrl(String releaseId) {
        if (!releaseId.startsWith("discogs:")) {
            return Optional.empty();
        }
        String[] parts = releaseId.split(":");
        if (parts.length != 3) {
            return Optional.empty();
        }
        String id = parts[2];

        DiscogsReleaseResponse response = client.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/releases/" + id);
                    if (!apiToken.isEmpty()) {
                        uriBuilder.queryParam("token", apiToken);
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .body(DiscogsReleaseResponse.class);

        if (response == null || response.videos() == null || response.videos().isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(response.videos().getFirst().uri());
    }

    public Optional<String> getPrimaryVideoUrlFallback(String releaseId, Exception e) {
        log.warn("Discogs getPrimaryVideoUrl fallback triggered for release ID '{}': {}", releaseId, e.getMessage());
        return Optional.empty();
    }

    @CircuitBreaker(name = "discogsClient", fallbackMethod = "getReleaseIdFromMarketplaceListingFallback")
    @Retry(name = "discogsClient")
    public Optional<Long> getReleaseIdFromMarketplaceListing(String listingId) {
        log.info("Resolving Discogs marketplace listing to release: {}", listingId);
        try {
            DiscogsMarketplaceListingResponse response = client.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/marketplace/listings/" + listingId);
                        if (!apiToken.isEmpty()) {
                            uriBuilder.queryParam("token", apiToken);
                        }
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .body(DiscogsMarketplaceListingResponse.class);

            if (response == null || response.release() == null) {
                log.warn("No release found for marketplace listing: {}", listingId);
                return Optional.empty();
            }
            return Optional.ofNullable(response.release().id());
        } catch (Exception ex) {
            log.error("Error fetching Discogs marketplace listing {}: {}", listingId, ex.getMessage());
            throw ex;
        }
    }

    public Optional<Long> getReleaseIdFromMarketplaceListingFallback(String listingId, Exception e) {
        log.warn("Discogs getReleaseIdFromMarketplaceListing fallback triggered for listing '{}': {}",
                listingId, e.getMessage());
        return Optional.empty();
    }

    @CircuitBreaker(name = "discogsClient", fallbackMethod = "getTracksFallback")
    @Retry(name = "discogsClient")
    @Override
    public List<TrackMetadata> getTracks(String releaseId) {
        log.info("Fetching tracklist from Discogs for release ID: {}", releaseId);

        if (!releaseId.startsWith("discogs:")) {
            log.warn("Invalid Discogs release ID format: {}", releaseId);
            return List.of();
        }

        String[] parts = releaseId.split(":");
        if (parts.length != 3 || !"release".equals(parts[1])) {
            log.warn("Invalid or non-release Discogs ID format: {}", releaseId);
            return List.of();
        }

        String id = parts[2];   // actual ID

        try {
            return getTracksFromRelease(id);
        } catch (Exception ex) {
            log.error("Error fetching tracklist from Discogs: {}", ex.getMessage());
            throw ex;
        }
    }

    public List<TrackMetadata> getTracksFallback(String releaseId, Exception e) {
        log.warn("Discogs getTracks fallback triggered for release ID '{}': {}",
            releaseId, e.getMessage());
        return List.of();
    }

    private List<TrackMetadata> getTracksFromRelease(String releaseId) {
        log.info("Fetching tracklist for release {}", releaseId);

        var response = client.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/releases/" + releaseId);
                    if (!apiToken.isEmpty()) {
                        uriBuilder.queryParam("token", apiToken);
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .body(DiscogsReleaseResponse.class);

        if (response == null || response.tracklist() == null) {
            log.warn("No tracklist found for release {}", releaseId);
            return List.of();
        }

        // Get album artist from response
        String albumArtist = extractArtistName(response);

        // Build TrackMetadata with sequential track numbers (1, 2, 3, ...)
        // Don't use Discogs position field as it may use vinyl notation (A1, A2, B1, B2)
        List<TrackMetadata> tracks = new ArrayList<>();
        int trackNumber = 1;

        for (var track : response.tracklist()) {
            String trackTitle = track.title();
            String trackArtist = albumArtist; // Default to album artist

            // Priority 1: Use track's artists field from Discogs API (most accurate)
            if (track.artists() != null && !track.artists().isEmpty()) {
                // Combine multiple artists using their 'join' field (e.g., "HATELOVE & Wanton")
                StringBuilder artistBuilder = new StringBuilder();
                for (int i = 0; i < track.artists().size(); i++) {
                    var artist = track.artists().get(i);
                    artistBuilder.append(cleanArtistName(artist.name()));

                    // Add join separator if not the last artist and join is provided
                    if (i < track.artists().size() - 1 && artist.join() != null && !artist.join().isEmpty()) {
                        artistBuilder.append(" ").append(artist.join()).append(" ");
                    }
                }
                trackArtist = artistBuilder.toString().trim();
                log.debug("Using per-track artist from Discogs API: '{}'", trackArtist);
            }
            // Priority 2: Try to parse from title if format is "Artist - Title"
            else if (trackTitle != null && trackTitle.contains(" - ")) {
                int dashIndex = trackTitle.indexOf(" - ");
                String possibleArtist = trackTitle.substring(0, dashIndex).trim();
                String possibleTitle = trackTitle.substring(dashIndex + 3).trim();

                // Only split if the artist part looks reasonable (not empty, not too long)
                if (!possibleArtist.isEmpty() && possibleArtist.length() < 100 && !possibleTitle.isEmpty()) {
                    trackArtist = cleanArtistName(possibleArtist);
                    trackTitle = possibleTitle;
                    log.debug("Parsed track artist from title: '{}' - '{}'", trackArtist, trackTitle);
                }
            }
            // Priority 3: Fall back to album artist (already set as default)

            tracks.add(new TrackMetadata(trackNumber, trackArtist, trackTitle));
            trackNumber++;
        }

        // Filter out headings (e.g., "Disc 1", "Side A") and re-number tracks
        List<TrackMetadata> finalTracks = new ArrayList<>();
        int finalTrackNumber = 1;
        for (TrackMetadata track : tracks) {
            if (!isHeading(track.title())) {
                finalTracks.add(new TrackMetadata(finalTrackNumber, track.artist(), track.title()));
                finalTrackNumber++;
            }
        }
        return finalTracks;
    }

    private static boolean isHeading(String title) {
        if (title == null) return false;
        String lowerTitle = title.toLowerCase();
        return lowerTitle.startsWith("disc ") || lowerTitle.startsWith("side ");
    }

    private String extractArtistName(DiscogsReleaseResponse response) {
        if (response.artists() != null && !response.artists().isEmpty()) {
            return cleanArtistName(response.artists().getFirst().name());
        }
        return "Unknown Artist";
    }

    private int parseTrackPosition(String position) {
        if (position == null || position.isEmpty()) {
            return 0;
        }
        // Handle formats like "1", "A1", "B2", etc.
        try {
            // Try to extract number from position (e.g., "A1" -> 1, "B2" -> 2)
            return Integer.parseInt(position.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String cleanArtistName(String artistName) {
        if (artistName == null || artistName.isEmpty()) {
            return artistName;
        }
        // Remove disambiguation suffix: " (number)" at the end
        return artistName.replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim();
    }

    private String cleanLabelName(String labelName) {
        if (labelName == null || labelName.isEmpty()) {
            return labelName;
        }
        // Remove disambiguation suffix: " (number)" at the end
        return labelName.replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim();
    }

    @Override
    public String getName() {
        return "discogs";
    }

    @Override
    public SearchEngine getSource() {
        return SearchEngine.DISCOGS;
    }

    @Override
    public String buildReleaseUrl(ReleaseMetadata release) {
        // Parse "discogs:release:456"
        if (release.id().startsWith("discogs:release:")) {
            String releaseId = release.id().substring("discogs:release:".length());
            return "https://www.discogs.com/release/" + releaseId;
        }
        return null;
    }

    @Override
    public ReleaseMetadata getReleaseMetadata(ReleaseMetadataFile metadataFile) {
        log.info("Refreshing metadata from Discogs for: {} - {}",
                metadataFile.artist(), metadataFile.title());

        // Always use sourceId which has the full format (discogs:master:123 or discogs:release:456)
        // masterId is just the numeric part used for display, not for API calls
        return getReleaseById(metadataFile.sourceId());
    }
}
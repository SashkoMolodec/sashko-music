package com.sashkomusic.mainagent.search.client.musicbrainz;

import com.sashkomusic.shared.model.MetadataSearchRequest;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.libraryagent.domain.model.ReleaseMetadataFile;
import com.sashkomusic.shared.model.SearchEngine;
import com.sashkomusic.shared.model.TrackMetadata;
import com.sashkomusic.mainagent.search.SearchEngineService;
import com.sashkomusic.mainagent.search.client.musicbrainz.exception.SearchNotCompleteException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class MusicBrainzClient implements SearchEngineService {

    private final RestClient client;
    private final MusicBrainzClient self;

    public MusicBrainzClient(RestClient.Builder builder, @Lazy MusicBrainzClient self) {
        this.client = builder
                .baseUrl("https://musicbrainz.org/ws/2")
                .defaultHeader("User-Agent", "SashkoMusicBot/1.0 ( contact@example.com )")
                .build();
        this.self = self;
    }

    @Override
    public List<ReleaseMetadata> searchReleases(MetadataSearchRequest request) {
        boolean hasRecording = !request.recording().isEmpty();
        boolean hasRelease = !request.release().isEmpty();

        if (hasRecording && !hasRelease) {
            log.info("Explicit track search detected, using recording endpoint");
            return self.searchByRecording(request);
        }

        // BROWSE case: no specific title to look up (style/year/label-type digging).
        // /release-group is one row per album concept instead of one row per pressing —
        // far less noisy than /release for "give me trance from 1994" style queries.
        boolean isBrowse = !hasRelease && !hasRecording
                && (!request.style().isEmpty() || (request.dateRange() != null && !request.dateRange().isEmpty()));
        if (isBrowse) {
            log.info("Browse-style search detected (no title), trying release-group endpoint first");
            var groupResults = self.searchByReleaseGroup(request);
            if (!groupResults.isEmpty()) {
                return groupResults;
            }
            log.info("release-group search returned nothing, falling back to /release");
        }

        var results = self.searchByRelease(request);

        if (results.isEmpty() && hasRelease) {
            results = tryWithCleanedReleaseTitle(request, results);
        }

        if (results.isEmpty() && hasRelease) {
            log.info("Release search failed, trying release title as recording name: '{}'", request.release());
            results = self.searchByRecording(request.withRecording(request.release()).withRelease(""));
        }

        if (results.isEmpty() && hasRecording) {
            log.info("No results from release search, trying explicit recording field");
            results = self.searchByRecording(request);
        }

        return results;
    }

    private List<ReleaseMetadata> tryWithCleanedReleaseTitle(MetadataSearchRequest request, List<ReleaseMetadata> results) {
        log.info("MusicBrainz search returned no results, trying fallback by cleaning release title.");

        String originalTitle = request.release();
        String cleanedTitle = originalTitle.replaceAll("\\s*\\([^)]*\\)", "").trim();

        if (!cleanedTitle.equals(originalTitle) && !cleanedTitle.isEmpty()) {
            MetadataSearchRequest fallbackRequest = request.withRelease(cleanedTitle);
            log.info("Fallback search with cleaned title: '{}'", cleanedTitle);

            results = self.searchByRelease(fallbackRequest);
        }
        return results;
    }

    @CircuitBreaker(name = "musicBrainzClient", fallbackMethod = "searchByReleaseFallback")
    @Retry(name = "musicBrainzClient")
    @RateLimiter(name = "musicBrainzClient")
    protected List<ReleaseMetadata> searchByRelease(MetadataSearchRequest request) {
        String luceneQuery = toLuceneQuery(request);
        log.info("Searching MusicBrainz release endpoint with query: {}", luceneQuery);

        try {
            var response = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/release")
                            .queryParam("query", luceneQuery)
                            .queryParam("fmt", "json")
                            .queryParam("limit", 100) // MusicBrainz hard caps limit at 100 — 150 silently errors
                            .build())
                    .retrieve()
                    .body(MusicBrainzSearchResponse.class);

            if (response == null || response.releases() == null || response.releases().isEmpty()) {
                return List.of();
            }

            return mapToGroupedDomain(response.releases());

        } catch (Exception ex) {
            log.warn("MusicBrainz API error (will retry): {}", ex.getMessage());
            throw new SearchNotCompleteException("Search failed due to API error.");
        }
    }

    protected List<ReleaseMetadata> searchByReleaseFallback(MetadataSearchRequest request, Exception e) {
        log.warn("MusicBrainz searchByRelease fallback triggered for query '{}': {}",
            toLuceneQuery(request), e.getMessage());
        return List.of();
    }

    /**
     * BROWSE-style search over /release-group — one row per album concept (dedup'd across
     * pressings), used when the caller has no specific artist/title to look up, only
     * style/year/type filters (e.g. "trance 1994").
     */
    @CircuitBreaker(name = "musicBrainzClient", fallbackMethod = "searchByReleaseGroupFallback")
    @Retry(name = "musicBrainzClient")
    @RateLimiter(name = "musicBrainzClient")
    protected List<ReleaseMetadata> searchByReleaseGroup(MetadataSearchRequest request) {
        String luceneQuery = toReleaseGroupLuceneQuery(request);
        log.info("Searching MusicBrainz release-group endpoint with query: {}", luceneQuery);

        try {
            var response = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/release-group")
                            .queryParam("query", luceneQuery)
                            .queryParam("fmt", "json")
                            .queryParam("limit", 100)
                            .build())
                    .retrieve()
                    .body(MusicBrainzReleaseGroupSearchResponse.class);

            if (response == null || response.releaseGroups() == null || response.releaseGroups().isEmpty()) {
                return List.of();
            }

            return response.releaseGroups().stream()
                    .map(this::mapReleaseGroup)
                    .filter(Objects::nonNull)
                    .toList();

        } catch (Exception ex) {
            log.warn("MusicBrainz release-group API error (will retry): {}", ex.getMessage());
            throw new SearchNotCompleteException("Search failed due to API error.");
        }
    }

    protected List<ReleaseMetadata> searchByReleaseGroupFallback(MetadataSearchRequest request, Exception e) {
        log.warn("MusicBrainz searchByReleaseGroup fallback triggered for query '{}': {}",
            toReleaseGroupLuceneQuery(request), e.getMessage());
        return List.of();
    }

    @CircuitBreaker(name = "musicBrainzClient", fallbackMethod = "searchByRecordingFallback")
    @Retry(name = "musicBrainzClient")
    @RateLimiter(name = "musicBrainzClient")
    protected List<ReleaseMetadata> searchByRecording(MetadataSearchRequest request) {
        String luceneQuery = toLuceneQuery(request);
        log.info("Searching MusicBrainz recording endpoint with query: {}", luceneQuery);

        try {
            var response = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/recording")
                            .queryParam("query", luceneQuery)
                            .queryParam("fmt", "json")
                            .queryParam("limit", 100)
                            .build())
                    .retrieve()
                    .body(RecordingSearchResponse.class);

            if (response == null || response.recordings() == null || response.recordings().isEmpty()) {
                return List.of();
            }

            var allReleases = response.recordings().stream()
                    .flatMap(recording -> {
                        if (recording.releases() == null) return Stream.empty();
                        return recording.releases().stream()
                                .map(release -> convertToReleases(release, recording.artistCredit()));
                    })
                    .toList();

            if (allReleases.isEmpty()) {
                return List.of();
            }

            return mapToGroupedDomain(allReleases);

        } catch (Exception ex) {
            log.warn("MusicBrainz recording API error (will retry): {}", ex.getMessage());
            throw new SearchNotCompleteException("Search failed due to API error.");
        }
    }

    protected List<ReleaseMetadata> searchByRecordingFallback(MetadataSearchRequest request, Exception e) {
        log.warn("MusicBrainz searchByRecording fallback triggered for query '{}': {}",
            toLuceneQuery(request), e.getMessage());
        return List.of();
    }

    private MusicBrainzSearchResponse.Release convertToReleases(
            RecordingSearchResponse.Release recordingRelease,
            List<RecordingSearchResponse.ArtistCredit> recordingArtistCredit) {

        // Use release's artist-credit if present, otherwise fallback to recording's artist-credit
        List<RecordingSearchResponse.ArtistCredit> sourceArtistCredit =
                recordingRelease.artistCredit() != null ? recordingRelease.artistCredit() : recordingArtistCredit;

        // Convert artist-credit from recording response to release response format
        List<MusicBrainzSearchResponse.ArtistCredit> artistCredit = null;
        if (sourceArtistCredit != null) {
            artistCredit = sourceArtistCredit.stream()
                    .map(ac -> new MusicBrainzSearchResponse.ArtistCredit(
                            ac.name(),
                            new MusicBrainzSearchResponse.Artist(
                                    ac.artist().id(),
                                    ac.artist().name(),
                                    ac.artist().sortName(),
                                    null // disambiguation not present in recording response
                            ),
                            ac.joinphrase()
                    ))
                    .toList();
        }

        return new MusicBrainzSearchResponse.Release(
                recordingRelease.id(),
                100, // Default score for releases from recording search
                recordingRelease.statusId(),
                null, // packagingId
                null, // artistCreditId
                0, // count
                recordingRelease.title(),
                recordingRelease.status(),
                null, // disambiguation
                null, // packaging
                null, // textRepresentation
                artistCredit,
                new MusicBrainzSearchResponse.ReleaseGroup(
                        recordingRelease.releaseGroup().id(),
                        null, // typeId
                        null, // primaryTypeId
                        recordingRelease.releaseGroup().title(),
                        recordingRelease.releaseGroup().primaryType(),
                        recordingRelease.releaseGroup().secondaryTypes() != null ? recordingRelease.releaseGroup().secondaryTypes() : List.of(),
                        List.of() // secondaryTypeIds
                ),
                recordingRelease.date(),
                recordingRelease.country(),
                null, // releaseEvents
                null, // barcode
                null, // asin
                null, // labelInfo
                recordingRelease.trackCount() != null ? recordingRelease.trackCount() : 0,
                null, // media
                null // tags
        );
    }

    private String toLuceneQuery(MetadataSearchRequest request) {
        List<String> conditions = new ArrayList<>();

        if (!request.artist().isEmpty()) {
            conditions.add("artist:\"" + escapeLucene(request.artist()) + "\"");
        }

        // Handle release and recording with OR logic when both are present
        boolean hasRelease = !request.release().isEmpty();
        boolean hasRecording = !request.recording().isEmpty();

        if (hasRelease && hasRecording) {
            String orCondition = String.format(
                "(release:\"%s\" OR recording:\"%s\")",
                escapeLucene(request.release()),
                escapeLucene(request.recording())
            );
            conditions.add(orCondition);
        } else if (hasRelease) {
            conditions.add("release:\"" + escapeLucene(request.release()) + "\"");
        } else if (hasRecording) {
            conditions.add("recording:\"" + escapeLucene(request.recording()) + "\"");
        }

        if (request.dateRange() != null && !request.dateRange().isEmpty()) {
            conditions.add(request.dateRange().toMusicBrainzQuery());
        }

        if (!request.format().isEmpty()) {
            conditions.add("format:\"" + escapeLucene(request.format()) + "\"");
        }

        if (!request.country().isEmpty()) {
            conditions.add("country:" + request.country());
        }

        if (!request.status().isEmpty()) {
            conditions.add("status:" + request.status());
        }

        if (!request.style().isEmpty()) {
            conditions.add("tag:\"" + escapeLucene(request.style()) + "\"");
        }

        if (!request.label().isEmpty()) {
            conditions.add("label:\"" + escapeLucene(request.label()) + "\"");
        }

        if (!request.catno().isEmpty()) {
            conditions.add("catno:\"" + escapeLucene(request.catno()) + "\"");
        }

        return conditions.isEmpty() ? "*" : String.join(" AND ", conditions);
    }

    private String escapeLucene(String value) {
        return value.replace("\"", "\\\"");
    }

    /**
     * Lucene query for /release-group. Indexed fields differ from /release: no
     * country/label/catno on release-group, and the date field is "firstreleasedate"
     * rather than "date".
     */
    private String toReleaseGroupLuceneQuery(MetadataSearchRequest request) {
        List<String> conditions = new ArrayList<>();

        if (!request.artist().isEmpty()) {
            conditions.add("artist:\"" + escapeLucene(request.artist()) + "\"");
        }

        if (!request.style().isEmpty()) {
            conditions.add("tag:\"" + escapeLucene(request.style()) + "\"");
        }

        var dateRange = request.dateRange();
        if (dateRange != null && !dateRange.isEmpty()) {
            conditions.add(dateRange.isSingleYear()
                    ? "firstreleasedate:" + dateRange.from()
                    : "firstreleasedate:[" + dateRange.from() + " TO " + dateRange.to() + "]");
        }

        if (!request.type().isEmpty()) {
            conditions.add("primarytype:\"" + escapeLucene(request.type()) + "\"");
        }

        if (!request.status().isEmpty()) {
            conditions.add("status:" + request.status());
        }

        return conditions.isEmpty() ? "*" : String.join(" AND ", conditions);
    }

    private List<ReleaseMetadata> mapToGroupedDomain(List<MusicBrainzSearchResponse.Release> releases) {
        Map<String, List<MusicBrainzSearchResponse.Release>> grouped = releases.stream()
                .filter(r -> r.releaseGroup() != null)
                .collect(Collectors.groupingBy(r -> r.releaseGroup().id()));

        return grouped.values().stream()
                .map(this::aggregateGroup)
                .sorted(
                        Comparator.comparing((ReleaseMetadata m) -> m.years().isEmpty() ? "0000" : m.years().getFirst())
                                .thenComparing(Comparator.comparingInt(ReleaseMetadata::score).reversed())
                                .thenComparingInt(r -> r.title().length())
                )
                .toList();
    }

    private ReleaseMetadata aggregateGroup(List<MusicBrainzSearchResponse.Release> groupReleases) {
        IntSummaryStatistics trackStats = groupReleases.stream()
                .mapToInt(this::getTrackCount)
                .filter(c -> c > 0)
                .summaryStatistics();

        int minTracks = trackStats.getCount() > 0 ? trackStats.getMin() : 0;
        int maxTracks = trackStats.getCount() > 0 ? trackStats.getMax() : 0;

        List<String> years = groupReleases.stream()
                .map(r -> extractYear(r.date()))
                .filter(y -> !y.equals("N/A"))
                .distinct()
                .sorted()
                .toList();

        List<String> types = groupReleases.stream()
                .map(r -> r.releaseGroup().primaryType())
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        List<String> tags = groupReleases.stream()
                .flatMap(r -> r.tags() != null ? r.tags().stream() : Stream.empty())
                .sorted(Comparator.comparingInt(MusicBrainzSearchResponse.Tag::count).reversed())
                .map(t -> t.name().toLowerCase())
                .distinct()
                .toList(); // Keep all tags, display will be limited

        var representative = groupReleases.stream().min(Comparator.comparingInt(MusicBrainzSearchResponse.Release::score).reversed()
                        .thenComparing(r -> "Official".equals(r.status()) ? 0 : 1)
                        .thenComparing(r -> r.date() != null ? r.date() : "9999")
                        .thenComparingInt(r -> r.title().length()))
                .orElse(groupReleases.getFirst());

        String coverUrl = getCoverUrl(representative);

        // Clean special characters that break file search
        String cleanArtist = clean(getArtistName(representative));
        String cleanTitle = clean(representative.title());

        // Extract label from labelInfo
        String label = groupReleases.stream()
                .filter(r -> r.labelInfo() != null && !r.labelInfo().isEmpty())
                .flatMap(r -> r.labelInfo().stream())
                .filter(li -> li.label() != null && li.label().name() != null)
                .map(li -> li.label().name())
                .findFirst()
                .orElse("");

        return new ReleaseMetadata(
                representative.id(),
                representative.releaseGroup().id(),
                SearchEngine.MUSICBRAINZ,
                cleanArtist,
                cleanTitle,
                representative.score(),
                years,
                types,
                minTracks,
                maxTracks,
                groupReleases.size(),
                List.of(),
                coverUrl,
                tags,
                label
        );
    }

    @Nullable
    private ReleaseMetadata mapReleaseGroup(MusicBrainzReleaseGroupSearchResponse.ReleaseGroup rg) {
        // Need a concrete release MBID to lazily fetch tracks later (getTracks/getReleaseById
        // both hit /release/{id}) — release-group search returns the underlying releases inline.
        var representativeRelease = (rg.releases() == null ? List.<MusicBrainzReleaseGroupSearchResponse.Release>of() : rg.releases()).stream()
                .filter(r -> "Official".equals(r.status()))
                .findFirst()
                .or(() -> rg.releases() == null || rg.releases().isEmpty()
                        ? Optional.empty()
                        : Optional.of(rg.releases().getFirst()))
                .orElse(null);
        if (representativeRelease == null) {
            return null;
        }

        String artist = clean(getArtistNameFromCredit(rg.artistCredit()));
        String title = clean(rg.title());

        List<String> years = rg.firstReleaseDate() != null && !rg.firstReleaseDate().isBlank()
                ? List.of(extractYear(rg.firstReleaseDate()))
                : List.of();

        List<String> types = new ArrayList<>();
        if (rg.primaryType() != null) types.add(rg.primaryType());
        if (rg.secondaryTypes() != null) types.addAll(rg.secondaryTypes());

        List<String> tags = rg.tags() != null
                ? rg.tags().stream()
                    .sorted(Comparator.comparingInt(MusicBrainzSearchResponse.Tag::count).reversed())
                    .map(t -> t.name().toLowerCase())
                    .distinct()
                    .toList()
                : List.of();

        String coverUrl = "https://coverartarchive.org/release-group/" + rg.id() + "/front-500";

        return new ReleaseMetadata(
                representativeRelease.id(),
                rg.id(),
                SearchEngine.MUSICBRAINZ,
                artist,
                title,
                rg.score(),
                years,
                types,
                0,
                0,
                rg.releases() != null ? rg.releases().size() : 1,
                List.of(),
                coverUrl,
                tags,
                ""
        );
    }

    private String getArtistNameFromCredit(List<MusicBrainzSearchResponse.ArtistCredit> artistCredit) {
        if (artistCredit == null || artistCredit.isEmpty()) {
            return "Unknown Artist";
        }
        StringBuilder sb = new StringBuilder();
        for (var credit : artistCredit) {
            sb.append(credit.name());
            if (credit.joinphrase() != null && !credit.joinphrase().isEmpty()) {
                sb.append(credit.joinphrase());
            }
        }
        return sb.toString().trim();
    }

    @Nullable
    private static String getCoverUrl(MusicBrainzSearchResponse.Release representative) {
        return representative.releaseGroup() != null && representative.releaseGroup().id() != null
                ? "https://coverartarchive.org/release-group/" + representative.releaseGroup().id() + "/front-500"
                : null;
    }

    private int getTrackCount(MusicBrainzSearchResponse.Release r) {
        if (r.trackCount() != null && r.trackCount() > 0) return r.trackCount();
        if (r.media() != null) {
            return r.media().stream()
                    .mapToInt(m -> m.trackCount() != null ? m.trackCount() : 0)
                    .sum();
        }
        return 0;
    }

    private String getArtistName(MusicBrainzSearchResponse.Release release) {
        if (release.artistCredit() != null && !release.artistCredit().isEmpty()) {
            // Combine multiple artists using joinphrase
            StringBuilder artistBuilder = new StringBuilder();
            var artistCredits = release.artistCredit();

            for (int i = 0; i < artistCredits.size(); i++) {
                var credit = artistCredits.get(i);
                artistBuilder.append(credit.name());

                // Add joinphrase if provided (e.g., " & ", " feat. ")
                if (credit.joinphrase() != null && !credit.joinphrase().isEmpty()) {
                    artistBuilder.append(credit.joinphrase());
                }
            }

            return artistBuilder.toString().trim();
        }
        return "Unknown Artist";
    }

    private String extractYear(String date) {
        if (date != null && date.length() >= 4) {
            return date.substring(0, 4);
        }
        return "N/A";
    }

    @CircuitBreaker(name = "musicBrainzClient", fallbackMethod = "getReleaseByIdFallback")
    @Retry(name = "musicBrainzClient")
    @RateLimiter(name = "musicBrainzClient")
    public ReleaseMetadata getReleaseById(String releaseId) {
        log.info("Fetching release metadata for ID: {}", releaseId);

        try {
            var release = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/release/{id}")
                            .queryParam("inc", "release-groups+tags+labels+recordings+artist-credits")
                            .queryParam("fmt", "json")
                            .build(releaseId))
                    .retrieve()
                    .body(MusicBrainzSearchResponse.Release.class);

            if (release == null) {
                log.warn("Release not found: {}", releaseId);
                return null;
            }

            List<String> years = List.of(extractYear(release.date()));
            List<String> types = release.releaseGroup() != null && release.releaseGroup().primaryType() != null
                    ? List.of(release.releaseGroup().primaryType())
                    : List.of();

            List<String> tags = release.tags() != null
                    ? release.tags().stream()
                    .sorted(Comparator.comparingInt(MusicBrainzSearchResponse.Tag::count).reversed())
                    .map(t -> t.name().toLowerCase())
                    .distinct()
                    .toList()
                    : List.of();

            String coverUrl = getCoverUrl(release);
            String cleanArtist = clean(getArtistName(release));
            String cleanTitle = clean(release.title());

            String label = release.labelInfo() != null && !release.labelInfo().isEmpty()
                    ? release.labelInfo().stream()
                    .filter(li -> li.label() != null && li.label().name() != null)
                    .map(li -> li.label().name())
                    .findFirst()
                    .orElse("")
                    : "";

            int trackCount = getTrackCount(release);

            List<TrackMetadata> tracks = getTracks(releaseId);

            return new ReleaseMetadata(
                    release.id(),
                    release.releaseGroup() != null ? release.releaseGroup().id() : null,
                    SearchEngine.MUSICBRAINZ,
                    cleanArtist,
                    cleanTitle,
                    100, // No score for direct fetch
                    years,
                    types,
                    trackCount,
                    trackCount,
                    1,
                    tracks,
                    coverUrl,
                    tags,
                    label
            );

        } catch (Exception ex) {
            log.error("Error fetching release metadata (will retry): {}", ex.getMessage());
            throw ex;
        }
    }

    public ReleaseMetadata getReleaseByIdFallback(String releaseId, Exception e) {
        log.warn("MusicBrainz getReleaseById fallback triggered for release ID '{}': {}",
            releaseId, e.getMessage());
        return null;
    }

    /** Resolves an artist name to an MBID — needed as input to ListenBrainz's similar-artists lookup. */
    @CircuitBreaker(name = "musicBrainzClient", fallbackMethod = "findArtistMbidFallback")
    @Retry(name = "musicBrainzClient")
    @RateLimiter(name = "musicBrainzClient")
    public Optional<String> findArtistMbid(String artistName) {
        var response = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/artist")
                        .queryParam("query", "artist:\"" + escapeLucene(artistName) + "\"")
                        .queryParam("fmt", "json")
                        .queryParam("limit", 1)
                        .build())
                .retrieve()
                .body(MusicBrainzArtistSearchResponse.class);

        if (response == null || response.artists() == null || response.artists().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(response.artists().getFirst().id());
    }

    public Optional<String> findArtistMbidFallback(String artistName, Exception e) {
        log.warn("MusicBrainz findArtistMbid fallback triggered for artist '{}': {}", artistName, e.getMessage());
        return Optional.empty();
    }

    @Override
    public List<TrackMetadata> getTracks(String releaseId) {
        log.info("Fetching tracklist for release ID: {}", releaseId);

        try {
            var release = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/release/{id}")
                            .queryParam("inc", "recordings+artist-credits")
                            .queryParam("fmt", "json")
                            .build(releaseId))
                    .retrieve()
                    .body(MusicBrainzSearchResponse.Release.class);

            if (release == null || release.media() == null) {
                log.warn("Release or media not found: {}", releaseId);
                return List.of();
            }

            // Get album artist as fallback
            String albumArtist = getArtistName(release);

            return release.media().stream()
                    .flatMap(media -> media.tracks() != null ? media.tracks().stream() : java.util.stream.Stream.empty())
                    .map(track -> {
                        int trackNumber = track.position();
                        String trackTitle = track.recording() != null ? track.recording().title() : track.title();

                        // Extract per-track artist from recording.artistCredit when available
                        String trackArtist = albumArtist; // Default to album artist

                        if (track.recording() != null && track.recording().artistCredit() != null
                                && !track.recording().artistCredit().isEmpty()) {
                            // Combine multiple artists using joinphrase (e.g., "HATELOVE & Wanton")
                            StringBuilder artistBuilder = new StringBuilder();
                            var artistCredits = track.recording().artistCredit();

                            for (int i = 0; i < artistCredits.size(); i++) {
                                var credit = artistCredits.get(i);
                                artistBuilder.append(credit.name());

                                // Add joinphrase if provided (e.g., " & ", " feat. ")
                                if (credit.joinphrase() != null && !credit.joinphrase().isEmpty()) {
                                    artistBuilder.append(credit.joinphrase());
                                }
                            }

                            trackArtist = artistBuilder.toString().trim();
                            log.debug("Using per-track artist from recording: '{}'", trackArtist);
                        }

                        return new TrackMetadata(trackNumber, trackArtist, trackTitle);
                    })
                    .toList();

        } catch (Exception ex) {
            log.error("Error fetching tracklist: {}", ex.getMessage());
            return List.of();
        }
    }

    @Override
    public String getName() {
        return "musicbrainz";
    }

    @Override
    public SearchEngine getSource() {
        return SearchEngine.MUSICBRAINZ;
    }

    @Override
    public String buildReleaseUrl(ReleaseMetadata release) {
        // Use release group ID (masterId) if available, otherwise release ID
        String id = release.masterId() != null ? release.masterId() : release.id();
        String type = release.masterId() != null ? "release-group" : "release";
        return "https://musicbrainz.org/" + type + "/" + id;
    }

    @Override
    public ReleaseMetadata getReleaseMetadata(ReleaseMetadataFile metadataFile) {
        log.info("Refreshing metadata from MusicBrainz for: {} - {}",
                metadataFile.artist(), metadataFile.title());
        return getReleaseById(metadataFile.sourceId());
    }

    private String clean(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        // Remove special characters that break file search
        return text.replaceAll("[*?\\[\\]{}|<>\"'`]", "").trim();
    }
}
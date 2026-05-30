package com.sashkomusic.mainagent.process;

import com.sashkomusic.agents.discovery.SearchRequestExtractor;
import com.sashkomusic.mainagent.search.client.bandcamp.BandcampClient;
import com.sashkomusic.mainagent.search.client.discogs.DiscogsClient;
import com.sashkomusic.mainagent.search.client.musicbrainz.MusicBrainzClient;
import com.sashkomusic.mainagent.shared.model.MetadataSearchRequest;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Run the same {@link MetadataSearchRequest} against MusicBrainz, Discogs and Bandcamp,
 * filter by title similarity, cap per-source and return the three lists together
 * as {@link SearchResults}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessFolderSearcher {

    private static final int MB_LIMIT = 4;
    private static final int DISCOGS_LIMIT = 4;
    private static final int BANDCAMP_LIMIT = 3;

    private final MusicBrainzClient musicBrainzClient;
    private final DiscogsClient discogsClient;
    private final BandcampClient bandcampClient;
    private final SearchRequestExtractor searchRequestExtractor;

    public SearchResults searchAll(MetadataSearchRequest request) {
        String title = request.getTitle();
        SearchResults results = new SearchResults(
                run(() -> musicBrainzClient.searchReleases(request), title, MB_LIMIT),
                run(() -> discogsClient.searchReleases(request), title, DISCOGS_LIMIT),
                run(() -> bandcampClient.searchReleases(request), title, BANDCAMP_LIMIT));

        if (results.isEmpty() || !hasArtistMatch(results.allResults(), request.artist())) {
            log.info("No artist match in results — retrying with AI-extracted query for artist='{}' release='{}'",
                    request.artist(), request.release());
            results = retryWithCleanQuery(request, title);
        }

        return results;
    }

    private SearchResults retryWithCleanQuery(MetadataSearchRequest original, String title) {
        try {
            String freeText = buildFreeText(original);
            MetadataSearchRequest clean = searchRequestExtractor.extract(freeText);
            log.info("Cleaned query: artist='{}' release='{}' type='{}' dateRange={}",
                    clean.artist(), clean.release(), clean.type(), clean.dateRange());
            return new SearchResults(
                    run(() -> musicBrainzClient.searchReleases(clean), title, MB_LIMIT),
                    run(() -> discogsClient.searchReleases(clean), title, DISCOGS_LIMIT),
                    run(() -> bandcampClient.searchReleases(clean), title, BANDCAMP_LIMIT));
        } catch (Exception e) {
            log.warn("Fallback search failed: {}", e.getMessage());
            return new SearchResults(List.of(), List.of(), List.of());
        }
    }

    private static boolean hasArtistMatch(List<ReleaseMetadata> results, String requestArtist) {
        if (requestArtist == null || requestArtist.isBlank()) return true;
        String needle = requestArtist.toLowerCase().trim();
        return results.stream().anyMatch(r ->
                r.artist() != null && r.artist().toLowerCase().contains(needle));
    }

    private static String buildFreeText(MetadataSearchRequest request) {
        var parts = new ArrayList<String>();
        if (request.artist() != null && !request.artist().isBlank()) parts.add(request.artist());
        if (request.release() != null && !request.release().isBlank()) parts.add(request.release());
        else if (request.recording() != null && !request.recording().isBlank()) parts.add(request.recording());
        return String.join(" ", parts);
    }

    private List<ReleaseMetadata> run(SourceSearcher searcher, String title, int limit) {
        try {
            List<ReleaseMetadata> results = searcher.search();
            log.info("Found {} results", results.size());

            List<ReleaseMetadata> filtered = filterByTitle(results, title);
            if (filtered.size() != results.size()) {
                log.info("{} results after filtering by title", filtered.size());
            }

            return filtered.stream().limit(limit).toList();
        } catch (Exception e) {
            log.error("Search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ReleaseMetadata> filterByTitle(List<ReleaseMetadata> results, String originalTitle) {
        if (originalTitle == null || originalTitle.isBlank()) {
            return results;
        }

        String normalized = originalTitle.toLowerCase().trim();
        Set<String> originalWords = new HashSet<>(java.util.Arrays.asList(normalized.split("\\s+")));

        return results.stream()
                .filter(release -> {
                    String releaseTitle = release.title().toLowerCase();
                    if (releaseTitle.contains(normalized) || normalized.contains(releaseTitle)) {
                        return true;
                    }
                    return Stream.of(releaseTitle.split("\\s+")).anyMatch(originalWords::contains);
                })
                .toList();
    }

    @FunctionalInterface
    private interface SourceSearcher {
        List<ReleaseMetadata> search();
    }

    public record SearchResults(
            List<ReleaseMetadata> mbResults,
            List<ReleaseMetadata> discogsResults,
            List<ReleaseMetadata> bandcampResults) {

        public List<ReleaseMetadata> allResults() {
            List<ReleaseMetadata> all = new ArrayList<>();
            all.addAll(mbResults);
            all.addAll(discogsResults);
            all.addAll(bandcampResults);
            return all;
        }

        public boolean isEmpty() {
            return mbResults.isEmpty() && discogsResults.isEmpty() && bandcampResults.isEmpty();
        }
    }
}

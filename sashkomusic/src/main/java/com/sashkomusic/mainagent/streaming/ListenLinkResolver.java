package com.sashkomusic.mainagent.streaming;

import com.sashkomusic.downloadagent.infrastructure.client.applemusic.ITunesSearchClient;
import com.sashkomusic.mainagent.search.client.discogs.DiscogsClient;
import com.sashkomusic.mainagent.search.client.ytmusic.YtMusicScraperClient;
import com.sashkomusic.shared.model.ReleaseMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Resolves where to listen to a release right now. Source order:
 *   - Bandcamp: the release page itself IS the listen link (masterId).
 *   - Discogs: community-curated YouTube links on the release page — the first is the listen link,
 *     the rest are per-track jump-offs worth showing alongside it.
 *   - MusicBrainz / Discogs-without-video: yt-music scraper search by artist+title, which itself
 *     degrades from an album match to a single track by the same artist.
 *   - Nothing matched: an LLM web search, the only unstructured source and therefore the last one
 *     tried — it costs an API round-trip and returns a URL nobody validated against a catalog.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListenLinkResolver {

    /** {@code title} is null when the source gives a bare URL with no track name attached. */
    public record ListenLink(String url, String title) {}

    /** {@code links} is never empty; the first entry is the one to lead with. */
    public record Result(String source, List<ListenLink> links) {
        public ListenLink primary() {
            return links.getFirst();
        }

        public List<ListenLink> alternates() {
            return links.subList(1, links.size());
        }
    }

    private static final String NOT_FOUND = "NONE";
    private static final int MAX_DISCOGS_VIDEOS = 8;

    private final DiscogsClient discogsClient;
    private final YtMusicScraperClient ytMusicScraperClient;
    private final ListenLinkWebSearch listenLinkWebSearch;
    private final ITunesSearchClient iTunesSearchClient;

    public Optional<Result> resolve(ReleaseMetadata release) {
        return fromCatalogs(release).or(() -> fromWebSearch(release));
    }

    /**
     * Apple Music album page via the public iTunes Search API. Offered alongside the main link
     * rather than inside the chain above: Discogs release pages show an Apple player, but the
     * Discogs API never exposes it, so the album has to be looked up in Apple's own catalog.
     * A search for "artist album" happily returns other artists' records, hence the artist check.
     */
    public Optional<ListenLink> appleMusic(ReleaseMetadata release) {
        try {
            return iTunesSearchClient.search(release.artist(), release.title()).stream()
                    .filter(result -> sameArtist(release.artist(), result.artistName()))
                    .findFirst()
                    .map(result -> new ListenLink(result.url(), result.albumName()));
        } catch (Exception e) {
            log.warn("Apple Music lookup failed for release {}: {}", release.id(), e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean sameArtist(String wanted, String found) {
        if (wanted == null || found == null) return false;
        String a = wanted.toLowerCase().strip();
        String b = found.toLowerCase().strip();
        return a.contains(b) || b.contains(a);
    }

    private Optional<Result> fromCatalogs(ReleaseMetadata release) {
        try {
            return switch (release.source()) {
                case BANDCAMP -> Optional.ofNullable(release.masterId())
                        .map(url -> new Result("bandcamp", List.of(new ListenLink(url, null))));
                case DISCOGS -> fromDiscogsVideos(release).or(() -> fromYtMusic(release));
                case MUSICBRAINZ -> fromYtMusic(release);
            };
        } catch (Exception e) {
            log.warn("Catalog listen-link lookup failed for release {}: {}", release.id(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Result> fromDiscogsVideos(ReleaseMetadata release) {
        // Discogs pages routinely carry the same video under several entries (per-track duplicates,
        // re-added uploads), and a 17-item list drowns the message it's attached to.
        List<ListenLink> videos = discogsClient.getVideos(release.id()).stream()
                .collect(Collectors.toMap(v -> v.uri(), v -> new ListenLink(v.uri(), v.title()),
                        (first, dup) -> first, LinkedHashMap::new))
                .values().stream()
                .limit(MAX_DISCOGS_VIDEOS)
                .toList();
        return videos.isEmpty() ? Optional.empty() : Optional.of(new Result("discogs", videos));
    }

    private Optional<Result> fromYtMusic(ReleaseMetadata release) {
        return ytMusicScraperClient.findAlbumUrl(release.artist(), release.title())
                .map(url -> new Result("yt music", List.of(new ListenLink(url, null))));
    }

    private Optional<Result> fromWebSearch(ReleaseMetadata release) {
        try {
            String answer = listenLinkWebSearch.findListenUrl(release.artist(), release.title());
            if (answer == null) return Optional.empty();
            String url = answer.trim();
            if (url.equalsIgnoreCase(NOT_FOUND) || !url.startsWith("http") || url.contains(" ")) {
                log.info("Web search found no listen link for '{} — {}'", release.artist(), release.title());
                return Optional.empty();
            }
            return Optional.of(new Result("web", List.of(new ListenLink(url, null))));
        } catch (Exception e) {
            log.warn("Web search listen-link lookup failed for release {}: {}", release.id(), e.getMessage());
            return Optional.empty();
        }
    }
}

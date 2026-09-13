package com.sashkomusic.mainagent.streaming;

import com.sashkomusic.mainagent.search.client.discogs.DiscogsClient;
import com.sashkomusic.mainagent.search.client.ytmusic.YtMusicScraperClient;
import com.sashkomusic.shared.model.ReleaseMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

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

    private final DiscogsClient discogsClient;
    private final YtMusicScraperClient ytMusicScraperClient;
    private final ListenLinkWebSearch listenLinkWebSearch;

    public Optional<Result> resolve(ReleaseMetadata release) {
        return fromCatalogs(release).or(() -> fromWebSearch(release));
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
        List<ListenLink> videos = discogsClient.getVideos(release.id()).stream()
                .map(v -> new ListenLink(v.uri(), v.title()))
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

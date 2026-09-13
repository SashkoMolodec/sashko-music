package com.sashkomusic.mainagent.streaming;

import com.sashkomusic.mainagent.search.client.discogs.DiscogsClient;
import com.sashkomusic.mainagent.search.client.ytmusic.YtMusicScraperClient;
import com.sashkomusic.shared.model.ReleaseMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Resolves a single "go listen to this now" link, preferring a real direct link over the
 * generic per-platform search links {@link StreamingFlowService} falls back to:
 *   - Bandcamp: the release page itself IS the listen link (masterId).
 *   - Discogs: first community-curated YouTube video on the release page, if any.
 *   - MusicBrainz / Discogs-without-video: yt-music scraper search by artist+title, which itself
 *     degrades from an album match to a single track by the same artist.
 *   - Nothing matched: an LLM web search, the only unstructured source and therefore the last one
 *     tried — it costs an API round-trip and returns a URL nobody validated against a catalog.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListenLinkResolver {

    public record ListenLink(String url, String source) {}

    private static final String NOT_FOUND = "NONE";

    private final DiscogsClient discogsClient;
    private final YtMusicScraperClient ytMusicScraperClient;
    private final ListenLinkWebSearch listenLinkWebSearch;

    public Optional<ListenLink> resolve(ReleaseMetadata release) {
        return fromCatalogs(release).or(() -> fromWebSearch(release));
    }

    private Optional<ListenLink> fromCatalogs(ReleaseMetadata release) {
        try {
            return switch (release.source()) {
                case BANDCAMP -> Optional.ofNullable(release.masterId())
                        .map(url -> new ListenLink(url, "bandcamp"));
                case DISCOGS -> discogsClient.getPrimaryVideoUrl(release.id())
                        .map(url -> new ListenLink(url, "discogs"))
                        .or(() -> fromYtMusic(release));
                case MUSICBRAINZ -> fromYtMusic(release);
            };
        } catch (Exception e) {
            log.warn("Catalog listen-link lookup failed for release {}: {}", release.id(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ListenLink> fromYtMusic(ReleaseMetadata release) {
        return ytMusicScraperClient.findAlbumUrl(release.artist(), release.title())
                .map(url -> new ListenLink(url, "yt music"));
    }

    private Optional<ListenLink> fromWebSearch(ReleaseMetadata release) {
        try {
            String answer = listenLinkWebSearch.findListenUrl(release.artist(), release.title());
            if (answer == null) return Optional.empty();
            String url = answer.trim();
            if (url.equalsIgnoreCase(NOT_FOUND) || !url.startsWith("http") || url.contains(" ")) {
                log.info("Web search found no listen link for '{} — {}'", release.artist(), release.title());
                return Optional.empty();
            }
            return Optional.of(new ListenLink(url, "web"));
        } catch (Exception e) {
            log.warn("Web search listen-link lookup failed for release {}: {}", release.id(), e.getMessage());
            return Optional.empty();
        }
    }
}

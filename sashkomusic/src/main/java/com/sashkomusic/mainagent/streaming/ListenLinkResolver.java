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
 * generic per-platform search links {@link StreamingFlowService} already builds:
 *   - Bandcamp: the release page itself IS the listen link (masterId).
 *   - Discogs: first community-curated YouTube video on the release page, if any.
 *   - MusicBrainz / Discogs-without-video: yt-music scraper search by artist+title.
 * All sources are structured data lookups — no LLM, no free-text web search, no "verify" step
 * needed, because the link is either attached to the exact release (Discogs video, Bandcamp URL)
 * or resolved by an artist+album match against YouTube Music's own catalog.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListenLinkResolver {

    private final DiscogsClient discogsClient;
    private final YtMusicScraperClient ytMusicScraperClient;

    public Optional<String> resolve(ReleaseMetadata release) {
        try {
            return switch (release.source()) {
                case BANDCAMP -> Optional.ofNullable(release.masterId());
                case DISCOGS -> discogsClient.getPrimaryVideoUrl(release.id())
                        .or(() -> ytMusicScraperClient.findAlbumUrl(release.artist(), release.title()));
                case MUSICBRAINZ -> ytMusicScraperClient.findAlbumUrl(release.artist(), release.title());
            };
        } catch (Exception e) {
            log.warn("ListenLinkResolver failed for release {}: {}", release.id(), e.getMessage());
            return Optional.empty();
        }
    }
}

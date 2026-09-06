package com.sashkomusic.mainagent.search;

import com.sashkomusic.mainagent.search.client.bandcamp.BandcampClient;
import com.sashkomusic.mainagent.search.client.discogs.DiscogsClient;
import com.sashkomusic.mainagent.search.client.musicbrainz.MusicBrainzClient;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class MetadataUrlFetcher {

    private static final Pattern DISCOGS_RELEASE =
            Pattern.compile("discogs\\.com[^?#]*/release/(\\d+)");
    private static final Pattern DISCOGS_MASTER =
            Pattern.compile("discogs\\.com[^?#]*/master/(\\d+)");
    private static final Pattern DISCOGS_MARKETPLACE_ITEM =
            Pattern.compile("discogs\\.com[^?#]*/(?:sell|shop)/item/(\\d+)");
    private static final Pattern MUSICBRAINZ_RELEASE =
            Pattern.compile("musicbrainz\\.org/release/([0-9a-f\\-]{36})");
    private static final Pattern BANDCAMP =
            Pattern.compile("bandcamp\\.com");

    private final DiscogsClient discogsClient;
    private final MusicBrainzClient musicBrainzClient;
    private final BandcampClient bandcampClient;

    public boolean isUrl(String input) {
        return input.startsWith("http://") || input.startsWith("https://");
    }

    public Optional<ReleaseMetadata> fetch(String url) {
        try {
            Matcher m;

            m = DISCOGS_RELEASE.matcher(url);
            if (m.find()) {
                log.info("Fetching Discogs release by URL: {}", url);
                return Optional.ofNullable(discogsClient.getReleaseById("discogs:release:" + m.group(1)));
            }

            m = DISCOGS_MASTER.matcher(url);
            if (m.find()) {
                log.info("Fetching Discogs master by URL: {}", url);
                return Optional.ofNullable(discogsClient.getReleaseById("discogs:master:" + m.group(1)));
            }

            m = DISCOGS_MARKETPLACE_ITEM.matcher(url);
            if (m.find()) {
                log.info("Fetching Discogs release via marketplace listing URL: {}", url);
                return discogsClient.getReleaseIdFromMarketplaceListing(m.group(1))
                        .map(releaseId -> discogsClient.getReleaseById("discogs:release:" + releaseId));
            }

            m = MUSICBRAINZ_RELEASE.matcher(url);
            if (m.find()) {
                log.info("Fetching MusicBrainz release by URL: {}", url);
                return Optional.ofNullable(musicBrainzClient.getReleaseById(m.group(1)));
            }

            if (BANDCAMP.matcher(url).find()) {
                log.info("Fetching Bandcamp release by URL: {}", url);
                return Optional.ofNullable(bandcampClient.getReleaseByUrl(url));
            }

        } catch (Exception e) {
            log.error("Failed to fetch metadata from URL {}: {}", url, e.getMessage());
        }

        log.warn("URL not recognised as Discogs/MusicBrainz/Bandcamp: {}", url);
        return Optional.empty();
    }
}

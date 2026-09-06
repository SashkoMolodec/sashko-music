package com.sashkomusic.mainagent.search;

import com.sashkomusic.mainagent.search.client.bandcamp.BandcampClient;
import com.sashkomusic.mainagent.search.client.discogs.DiscogsClient;
import com.sashkomusic.mainagent.search.client.musicbrainz.MusicBrainzClient;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetadataUrlFetcherTest {

    private final DiscogsClient discogsClient = mock(DiscogsClient.class);
    private final MusicBrainzClient musicBrainzClient = mock(MusicBrainzClient.class);
    private final BandcampClient bandcampClient = mock(BandcampClient.class);
    private final MetadataUrlFetcher fetcher =
            new MetadataUrlFetcher(discogsClient, musicBrainzClient, bandcampClient);

    private static ReleaseMetadata release(String id) {
        return new ReleaseMetadata(id, null, SearchEngine.DISCOGS, "Artist", "Title", 100,
                List.of(), List.of(), 0, 0, 1, List.of(), null, List.of(), "");
    }

    @Test
    void resolves_release_url() {
        when(discogsClient.getReleaseById("discogs:release:249504")).thenReturn(release("discogs:release:249504"));

        Optional<ReleaseMetadata> result = fetcher.fetch("https://www.discogs.com/release/249504-Artist-Title");

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("discogs:release:249504");
    }

    @Test
    void resolves_marketplace_shop_item_url_via_listing_lookup() {
        when(discogsClient.getReleaseIdFromMarketplaceListing("3323605536")).thenReturn(Optional.of(249504L));
        when(discogsClient.getReleaseById("discogs:release:249504")).thenReturn(release("discogs:release:249504"));

        Optional<ReleaseMetadata> result = fetcher.fetch("https://www.discogs.com/shop/item/3323605536");

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("discogs:release:249504");
    }

    @Test
    void resolves_marketplace_sell_item_url_via_listing_lookup() {
        when(discogsClient.getReleaseIdFromMarketplaceListing("172723812")).thenReturn(Optional.of(249504L));
        when(discogsClient.getReleaseById("discogs:release:249504")).thenReturn(release("discogs:release:249504"));

        Optional<ReleaseMetadata> result = fetcher.fetch("https://www.discogs.com/sell/item/172723812");

        assertThat(result).isPresent();
    }

    @Test
    void marketplace_listing_with_no_release_yields_empty() {
        when(discogsClient.getReleaseIdFromMarketplaceListing(eq("999"))).thenReturn(Optional.empty());

        Optional<ReleaseMetadata> result = fetcher.fetch("https://www.discogs.com/shop/item/999");

        assertThat(result).isEmpty();
    }

    @Test
    void unrecognised_url_yields_empty() {
        Optional<ReleaseMetadata> result = fetcher.fetch("https://example.com/whatever");

        assertThat(result).isEmpty();
    }
}

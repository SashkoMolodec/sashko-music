package com.sashkomusic.mainagent.search;

import com.sashkomusic.shared.model.DateRange;
import com.sashkomusic.shared.model.MetadataSearchRequest;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.SearchEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AggregatedSearchServiceLabelTest {

    private final SearchEngineService discogs = mock(SearchEngineService.class);

    private final AggregatedSearchService service =
            new AggregatedSearchService(Map.of(SearchEngine.DISCOGS, discogs), Runnable::run);

    @Test
    void a_label_lookup_is_not_cut_down_to_the_usual_twenty() {
        // Twenty rows of one prolific artist must not leave a label answer with a single stack.
        when(discogs.searchReleases(any())).thenReturn(IntStream.range(0, 40)
                .mapToObj(i -> onLabel("Artist " + i, "Tresor")).toList());

        assertThat(service.search(label("Tresor")).releases()).hasSize(40);
    }

    @Test
    void only_releases_on_the_label_survive() {
        when(discogs.searchReleases(any())).thenReturn(List.of(
                onLabel("Jeff Mills", "Tresor"), onLabel("Somebody", "Tresor Pressing Plant"), onLabel("Nobody", "")));

        assertThat(service.search(label("Tresor")).releases()).extracting(ReleaseMetadata::artist)
                .containsExactly("Jeff Mills");
    }

    private static MetadataSearchRequest label(String name) {
        return new MetadataSearchRequest(null, "", "", "", DateRange.empty(), "", "", "", "", "", name, "");
    }

    private static ReleaseMetadata onLabel(String artist, String label) {
        return new ReleaseMetadata("discogs:release:" + artist, null, SearchEngine.DISCOGS, artist, "Title", 100,
                List.of("1992"), List.of("Album"), 0, 0, 1, List.of(), null, List.of(), label);
    }
}

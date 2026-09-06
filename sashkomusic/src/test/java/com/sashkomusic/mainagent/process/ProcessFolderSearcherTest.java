package com.sashkomusic.mainagent.process;

import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessFolderSearcherTest {

    private static ReleaseMetadata release(String id, SearchEngine source) {
        return new ReleaseMetadata(id, null, source, "Artist", "Title", 100,
                List.of(), List.of(), 0, 0, 1, List.of(), null, List.of(), "");
    }

    @Test
    void withKnownRelease_prepends_as_first_option() {
        var results = new ProcessFolderSearcher.SearchResults(
                List.of(),
                List.of(release("mb:1", SearchEngine.MUSICBRAINZ)),
                List.of(release("discogs:1", SearchEngine.DISCOGS)),
                List.of());

        var known = release("discogs:99", SearchEngine.DISCOGS);
        var withKnown = results.withKnownRelease(known);

        assertThat(withKnown.allResults()).extracting(ReleaseMetadata::id)
                .containsExactly("discogs:99", "mb:1", "discogs:1");
    }

    @Test
    void withKnownRelease_dedupes_if_already_present_in_source_results() {
        var duplicate = release("discogs:1", SearchEngine.DISCOGS);
        var results = new ProcessFolderSearcher.SearchResults(
                List.of(),
                List.of(),
                List.of(duplicate),
                List.of());

        var withKnown = results.withKnownRelease(duplicate);

        assertThat(withKnown.allResults()).extracting(ReleaseMetadata::id).containsExactly("discogs:1");
    }

    @Test
    void isEmpty_considers_knownResults() {
        var results = new ProcessFolderSearcher.SearchResults(List.of(), List.of(), List.of(), List.of());
        assertThat(results.isEmpty()).isTrue();

        var withKnown = results.withKnownRelease(release("mb:1", SearchEngine.MUSICBRAINZ));
        assertThat(withKnown.isEmpty()).isFalse();
    }
}

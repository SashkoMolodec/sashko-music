package com.sashkomusic.mainagent.search;

import com.sashkomusic.shared.model.DateRange;
import com.sashkomusic.shared.model.MetadataSearchRequest;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.SearchEngine;
import com.sashkomusic.shared.model.TrackMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseMatchValidatorTest {

    @Test
    void accepts_exact_artist_and_title() {
        var match = ReleaseMatchValidator.evaluate(request("Burial", "Untrue", ""), release("Burial", "Untrue"));

        assertThat(match).isEqualTo(ReleaseMatchValidator.Match.EXACT);
    }

    @Test
    void rejects_same_title_by_a_different_artist() {
        var match = ReleaseMatchValidator.evaluate(request("Adjust", "Mist", "Mist"), release("The Magic Number", "Mist"));

        assertThat(match).isEqualTo(ReleaseMatchValidator.Match.NONE);
    }

    @Test
    void accepts_compilation_once_its_tracklist_names_the_requested_artist() {
        var compilation = release("Various", "Radio Universe - Ultima")
                .withTracks(List.of(new TrackMetadata(1, "Somebody Else", "Opener"),
                        new TrackMetadata(2, "Adjust (BE)", "Mist")));

        var match = ReleaseMatchValidator.evaluate(request("Adjust (BE)", "Mist", "Mist"), compilation);

        assertThat(match).isEqualTo(ReleaseMatchValidator.Match.TRACK);
    }

    @Test
    void rejects_compilation_carrying_the_track_by_someone_else() {
        var compilation = release("Various", "Some Comp")
                .withTracks(List.of(new TrackMetadata(1, "Other Artist", "Mist")));

        var match = ReleaseMatchValidator.evaluate(request("Adjust (BE)", "Mist", "Mist"), compilation);

        assertThat(match).isEqualTo(ReleaseMatchValidator.Match.NONE);
    }

    @Test
    void compilation_needs_a_tracklist_lookup_before_a_verdict() {
        var compilation = release("Various", "Radio Universe - Ultima");

        assertThat(ReleaseMatchValidator.needsTracklistCheck(request("Adjust (BE)", "Mist", "Mist"), compilation)).isTrue();
        // An album-titled match is already decided — no point spending an API call on it.
        assertThat(ReleaseMatchValidator.needsTracklistCheck(request("Burial", "Untrue", "Untrue"), release("Burial", "Untrue"))).isFalse();
    }

    @Test
    void ignores_bracketed_disambiguation_on_artist_names() {
        // "Adjust (BE)" as the user types it, "Adjust (2)" as Discogs disambiguates it — same artist.
        assertThat(ReleaseMatchValidator.artistMatches("Adjust (2)", "Adjust (BE)")).isTrue();
    }

    @Test
    void does_not_match_a_title_that_merely_contains_the_word() {
        var match = ReleaseMatchValidator.evaluate(request("Adjust", "Mist", ""), release("Adjust", "Mistake"));

        assertThat(match).isEqualTo(ReleaseMatchValidator.Match.NONE);
    }

    @Test
    void accepts_edition_suffix_as_partial() {
        var match = ReleaseMatchValidator.evaluate(request("Burial", "Untrue", ""), release("Burial", "Untrue Remastered"));

        assertThat(match).isEqualTo(ReleaseMatchValidator.Match.PARTIAL);
    }

    @Test
    void artist_only_query_accepts_the_whole_discography() {
        assertThat(ReleaseMatchValidator.evaluate(request("Burial", "", ""), release("Burial", "Rival Dealer")))
                .isEqualTo(ReleaseMatchValidator.Match.EXACT);
        assertThat(ReleaseMatchValidator.evaluate(request("Burial", "", ""), release("Four Tet", "Rounds")))
                .isEqualTo(ReleaseMatchValidator.Match.NONE);
    }

    @Test
    void browse_query_has_nothing_to_validate_against_and_keeps_engine_results() {
        var browse = new MetadataSearchRequest(null, "", "", "", DateRange.empty(),
                "", "", "", "", "trance", "", "");

        assertThat(ReleaseMatchValidator.evaluate(browse, release("Whoever", "Whatever")))
                .isEqualTo(ReleaseMatchValidator.Match.PARTIAL);
    }

    private static MetadataSearchRequest request(String artist, String release, String recording) {
        return new MetadataSearchRequest(null, artist, release, recording, DateRange.empty(),
                "", "", "", "", "", "", "");
    }

    private static ReleaseMetadata release(String artist, String title) {
        return new ReleaseMetadata("discogs:release:1", null, SearchEngine.DISCOGS, artist, title, 100,
                List.of("2023"), List.of("Album"), 0, 0, 1, List.of(), null, List.of(), null);
    }
}

package com.sashkomusic.agents.discovery;

import com.sashkomusic.shared.model.DateRange;
import com.sashkomusic.shared.model.MetadataSearchRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRequestNormalizerTest {

    @Test
    void drops_a_country_that_is_really_an_artist_disambiguator() {
        var extracted = request("Adjust", "Fractured Elements", "", "BE");

        var normalized = SearchRequestNormalizer.normalize("Adjust (BE) - Fractured Elements", extracted);

        assertThat(normalized.country()).isEmpty();
    }

    @Test
    void keeps_a_country_the_user_actually_asked_for() {
        var extracted = request("", "", "", "DE");

        var normalized = SearchRequestNormalizer.normalize("German techno 90s", extracted);

        assertThat(normalized.country()).isEqualTo("DE");
    }

    @Test
    void fills_the_track_field_for_a_bare_artist_title_query() {
        // Without this the Discogs free-text lookup and the tracklist confirmation pass never run,
        // so a track that only exists on a Various-artists compilation cannot be found at all.
        var extracted = request("Adjust", "Fractured Elements", "", "");

        var normalized = SearchRequestNormalizer.normalize("Adjust (BE) - Fractured Elements", extracted);

        assertThat(normalized.recording()).isEqualTo("Fractured Elements");
        assertThat(normalized.release()).isEqualTo("Fractured Elements");
    }

    @ParameterizedTest
    @CsvSource({
            "Пошукай трек Adjust - Fractured Elements, true",
            "Adjust - Fractured Elements track, true",
            "Adjust - Fractured Elements, true",
            "Adjust альбом Fractured Elements, false",
            "Adjust Fractured Elements LP, false",
            "Adjust Fractured Elements vinyl, false",
    })
    void fills_the_track_field_unless_the_query_names_an_album(String query, boolean expectRecording) {
        var normalized = SearchRequestNormalizer.normalize(query, request("Adjust", "Fractured Elements", "", ""));

        assertThat(normalized.recording().isBlank()).isEqualTo(!expectRecording);
    }

    @Test
    void a_track_word_wins_over_an_album_word_in_the_same_query() {
        var normalized = SearchRequestNormalizer.normalize(
                "трек Fractured Elements з альбому Air II", request("Adjust", "Fractured Elements", "", ""));

        assertThat(normalized.recording()).isEqualTo("Fractured Elements");
    }

    @Test
    void leaves_an_already_extracted_track_alone() {
        var normalized = SearchRequestNormalizer.normalize(
                "Adjust - Mist", request("Adjust", "Ultima", "Mist", ""));

        assertThat(normalized.recording()).isEqualTo("Mist");
        assertThat(normalized.release()).isEqualTo("Ultima");
    }

    @Test
    void a_label_query_stays_a_label_when_the_model_reads_the_name_as_an_artist() {
        var normalized = SearchRequestNormalizer.normalize("лейбл Tresor", request("Tresor", "", "", ""));

        assertThat(normalized.isLabelLookup()).isTrue();
        assertThat(normalized.label()).isEqualTo("Tresor");
        assertThat(normalized.artist()).isEmpty();
    }

    @Test
    void the_word_label_inside_an_ordinary_query_changes_nothing() {
        var normalized = SearchRequestNormalizer.normalize("Jeff Mills на лейблі Tresor", request("Jeff Mills", "", "", ""));

        assertThat(normalized.artist()).isEqualTo("Jeff Mills");
        assertThat(normalized.isLabelLookup()).isFalse();
    }

    private static MetadataSearchRequest request(String artist, String release, String recording, String country) {
        return new MetadataSearchRequest(null, artist, release, recording, DateRange.empty(),
                "", "", country, "", "", "", "");
    }
}

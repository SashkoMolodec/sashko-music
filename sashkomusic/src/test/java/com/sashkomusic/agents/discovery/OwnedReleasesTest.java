package com.sashkomusic.agents.discovery;

import com.sashkomusic.libraryagent.domain.model.OwnedRelease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OwnedReleasesTest {

    private final OwnedReleases owned = OwnedReleases.of(List.of(
            new OwnedRelease("Cybotron", "Enter (Deluxe Edition)"),
            new OwnedRelease("Juan Atkins, Moritz von Oswald", "Borderland"),
            new OwnedRelease("The Other People Place", "Lifestyles of the Laptop Café"),
            new OwnedRelease("Арніка", "Естрадний Ансамбль Арніка")));

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Cybotron          | Enter",
            "cybotron          | ENTER (deluxe edition) [flac]",
            "Moritz von Oswald | Borderland",
            "Juan Atkins       | Borderland",
            "Other People Place| Lifestyles Of The Laptop Cafe",
            "арніка            | естрадний ансамбль арніка",
    })
    void owns_a_release_however_the_names_are_dressed(String artist, String title) {
        assertThat(owned.owns(artist, title)).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Cybotron    | Clear",
            "Model 500   | Enter",
            "Juan Atkins | Deep Space",
    })
    void a_different_artist_or_title_is_not_owned(String artist, String title) {
        assertThat(owned.owns(artist, title)).isFalse();
    }

    @Test
    void the_prompt_lists_one_release_per_line_and_says_when_the_library_is_empty() {
        assertThat(owned.forPrompt().lines()).first().isEqualTo("Cybotron — Enter (Deluxe Edition)");
        assertThat(OwnedReleases.of(List.of()).forPrompt()).isEqualTo("(nothing yet)");
    }
}

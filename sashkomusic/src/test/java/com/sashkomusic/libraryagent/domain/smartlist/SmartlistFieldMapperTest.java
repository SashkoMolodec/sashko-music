package com.sashkomusic.libraryagent.domain.smartlist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmartlistFieldMapperTest {

    private final SmartlistFieldMapper mapper = new SmartlistFieldMapper();

    @ParameterizedTest
    @CsvSource({
            "year, TDRC",
            "comment, COMM",
            "label, PUBLISHER",
            "genre, TCON",
            "rating, RATING",
            "GENRE, TCON"
    })
    void maps_known_fields(String field, String expected) {
        assertThat(mapper.tagName(field)).isEqualTo(expected);
    }

    @Test
    void rejects_unknown_field() {
        assertThatThrownBy(() -> mapper.tagName("bpm"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"1, 51", "2, 102", "3, 153", "4, 204", "5, 255", "0, 0"})
    void converts_stars_to_wmp(int stars, int wmp) {
        assertThat(mapper.starsToWmp(stars)).isEqualTo(wmp);
    }

    @Test
    void rejects_out_of_range_stars() {
        assertThatThrownBy(() -> mapper.starsToWmp(6))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

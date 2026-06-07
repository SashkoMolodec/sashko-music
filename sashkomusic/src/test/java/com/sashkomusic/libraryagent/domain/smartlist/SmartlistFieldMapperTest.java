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

    @Test
    void range_op_supported_only_on_rating_and_year() {
        assertThat(mapper.isRangeField("rating")).isTrue();
        assertThat(mapper.isRangeField("year")).isTrue();
        assertThat(mapper.isRangeField("genre")).isFalse();
        assertThat(mapper.isRangeField("comment")).isFalse();
        assertThat(mapper.isRangeField("label")).isFalse();
    }

    @Test
    void only_rating_uses_stars_scale() {
        assertThat(mapper.usesStarsScale("rating")).isTrue();
        assertThat(mapper.usesStarsScale("year")).isFalse();
    }
}

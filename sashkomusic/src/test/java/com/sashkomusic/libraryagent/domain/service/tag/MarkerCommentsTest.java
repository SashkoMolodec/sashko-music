package com.sashkomusic.libraryagent.domain.service.tag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class MarkerCommentsTest {

    @Test
    void finds_only_parts_wholly_in_parentheses() {
        assertThat(MarkerComments.markersIn("tool; E3; (Detroit Techno); nice (live); (raw modern techno); (detroit techno)"))
                .containsExactly("detroit techno", "raw modern techno");
    }

    @Test
    void a_missing_comment_has_no_markers() {
        assertThat(MarkerComments.markersIn(null)).isEmpty();
        assertThat(MarkerComments.markersIn("  ")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "tool; E3; (detroit techno); electronic, electro, techno | detroit techno | tool; E3; electronic, electro, techno",
            "(detroit techno)                                      | detroit techno | ''",
            "banger; (Detroit Techno); E4; ( detroit techno )      | detroit techno | banger; E4",
            "tool; nice (detroit techno) set                       | detroit techno | tool; nice (detroit techno) set",
            "tool; (raw modern techno)                             | detroit techno | tool; (raw modern techno)",
    })
    void cuts_out_every_copy_of_the_marker_and_keeps_the_rest(String comment, String marker, String expected) {
        assertThat(MarkerComments.without(comment, marker)).isEqualTo(expected);
    }
}

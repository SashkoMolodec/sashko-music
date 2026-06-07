package com.sashkomusic.libraryagent.domain.smartlist;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for SmartlistEvaluator.describe() — validates OR/AND grouping logic
 * without requiring a database connection.
 */
class SmartlistEvaluatorDescribeTest {

    private final SmartlistEvaluator evaluator = new SmartlistEvaluator(new SmartlistFieldMapper());

    @Test
    void single_condition_no_parens() {
        SmartlistDsl dsl = new SmartlistDsl(List.of(
                new SmartlistDsl.ContainsCondition("genre", "house")
        ));
        assertThat(evaluator.describe(dsl)).isEqualTo("genre contains \"house\"");
    }

    @Test
    void different_fields_joined_with_and() {
        SmartlistDsl dsl = new SmartlistDsl(List.of(
                new SmartlistDsl.ContainsCondition("genre", "house"),
                new SmartlistDsl.GteCondition("rating", 4)
        ));
        assertThat(evaluator.describe(dsl)).isEqualTo("genre contains \"house\" AND rating >= 4");
    }

    @Test
    void same_field_multiple_contains_joined_with_or_in_parens() {
        SmartlistDsl dsl = new SmartlistDsl(List.of(
                new SmartlistDsl.ContainsCondition("genre", "latina"),
                new SmartlistDsl.ContainsCondition("genre", "bolero")
        ));
        assertThat(evaluator.describe(dsl))
                .isEqualTo("(genre contains \"latina\" OR genre contains \"bolero\")");
    }

    @Test
    void three_same_field_conditions_all_ored() {
        SmartlistDsl dsl = new SmartlistDsl(List.of(
                new SmartlistDsl.ContainsCondition("genre", "house"),
                new SmartlistDsl.ContainsCondition("genre", "techno"),
                new SmartlistDsl.ContainsCondition("genre", "trance")
        ));
        assertThat(evaluator.describe(dsl))
                .isEqualTo("(genre contains \"house\" OR genre contains \"techno\" OR genre contains \"trance\")");
    }

    @Test
    void mixed_or_groups_and_single_fields() {
        // genre: OR group; year: single; rating: single → (genre OR genre) AND year AND rating
        SmartlistDsl dsl = new SmartlistDsl(List.of(
                new SmartlistDsl.ContainsCondition("genre", "latina"),
                new SmartlistDsl.ContainsCondition("genre", "bolero"),
                new SmartlistDsl.LtCondition("year", 1990),
                new SmartlistDsl.GteCondition("rating", 3)
        ));
        String desc = evaluator.describe(dsl);
        assertThat(desc).startsWith("(genre contains \"latina\" OR genre contains \"bolero\")");
        assertThat(desc).contains("AND year < 1990");
        assertThat(desc).contains("AND rating >= 3");
    }

    @Test
    void sublibrary_is_condition_in_describe() {
        SmartlistDsl dsl = new SmartlistDsl(List.of(
                new SmartlistDsl.IsCondition("sublibrary", "working")
        ));
        assertThat(evaluator.describe(dsl)).isEqualTo("sublibrary is \"working\"");
    }

    @Test
    void sublibrary_combined_with_genre_or_group() {
        SmartlistDsl dsl = new SmartlistDsl(List.of(
                new SmartlistDsl.ContainsCondition("genre", "techno"),
                new SmartlistDsl.ContainsCondition("genre", "house"),
                new SmartlistDsl.IsCondition("sublibrary", "working")
        ));
        String desc = evaluator.describe(dsl);
        assertThat(desc).contains("(genre contains \"techno\" OR genre contains \"house\")");
        assertThat(desc).contains("sublibrary is \"working\"");
        assertThat(desc).contains(" AND ");
    }

    @Test
    void range_condition_describe() {
        SmartlistDsl dsl = new SmartlistDsl(List.of(
                new SmartlistDsl.RangeCondition("year", 1970, 1989)
        ));
        assertThat(evaluator.describe(dsl)).isEqualTo("year 1970…1989");
    }

    @Test
    void is_null_condition_describe() {
        SmartlistDsl dsl = new SmartlistDsl(List.of(
                new SmartlistDsl.IsCondition("rating", null)
        ));
        assertThat(evaluator.describe(dsl)).isEqualTo("rating is null");
    }

    @Test
    void empty_dsl_returns_no_conditions() {
        assertThat(evaluator.describe(new SmartlistDsl(List.of()))).isEqualTo("(no conditions)");
        assertThat(evaluator.describe(null)).isEqualTo("(no conditions)");
    }
}

package com.sashkomusic.libraryagent.domain.smartlist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SmartlistDslSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundtrips_contains_range_and_is_conditions() throws Exception {
        SmartlistDsl dsl = new SmartlistDsl(List.of(
                new SmartlistDsl.ContainsCondition("genre", "house"),
                new SmartlistDsl.RangeCondition("rating", 4, 5),
                new SmartlistDsl.IsCondition("year", "2024"),
                new SmartlistDsl.IsCondition("rating", null)
        ));

        String json = mapper.writeValueAsString(dsl);
        assertThat(json).contains("\"op\":\"contains\"", "\"op\":\"range\"", "\"op\":\"is\"");

        SmartlistDsl parsed = mapper.readValue(json, SmartlistDsl.class);
        assertThat(parsed.conditions()).hasSize(4);
        assertThat(parsed.conditions().get(2)).isEqualTo(new SmartlistDsl.IsCondition("year", "2024"));
        assertThat(parsed.conditions().get(3)).isEqualTo(new SmartlistDsl.IsCondition("rating", null));
    }

    @Test
    void parses_is_null_from_json_with_explicit_null() throws Exception {
        String json = "{\"conditions\":[{\"op\":\"is\",\"field\":\"rating\",\"value\":null}]}";
        SmartlistDsl parsed = mapper.readValue(json, SmartlistDsl.class);
        assertThat(parsed.conditions()).singleElement()
                .isInstanceOfSatisfying(SmartlistDsl.IsCondition.class, c -> {
                    assertThat(c.field()).isEqualTo("rating");
                    assertThat(c.value()).isNull();
                });
    }
}

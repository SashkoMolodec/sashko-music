package com.sashkomusic.libraryagent.domain.smartlist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SmartlistServiceParseTest {

    private final SmartlistService sut = new SmartlistService(
            mock(SmartlistRepository.class),
            mock(SmartlistEvaluator.class),
            mock(SmartlistM3uWriter.class),
            new ObjectMapper()
    );

    @Test
    void parses_raw_json() {
        SmartlistDsl dsl = sut.parse("{\"conditions\":[{\"op\":\"contains\",\"field\":\"genre\",\"value\":\"house\"}]}");
        assertThat(dsl.conditions()).singleElement()
                .isEqualTo(new SmartlistDsl.ContainsCondition("genre", "house"));
    }

    @Test
    void parses_json_wrapped_in_markdown_fence_with_language() {
        String fenced = """
                ```json
                {"conditions":[{"op":"contains","field":"genre","value":"house"}]}
                ```
                """;
        SmartlistDsl dsl = sut.parse(fenced);
        assertThat(dsl.conditions()).singleElement()
                .isEqualTo(new SmartlistDsl.ContainsCondition("genre", "house"));
    }

    @Test
    void parses_json_wrapped_in_bare_fence() {
        String fenced = """
                ```
                {"conditions":[{"op":"contains","field":"genre","value":"house"}]}
                ```""";
        SmartlistDsl dsl = sut.parse(fenced);
        assertThat(dsl.conditions()).singleElement()
                .isEqualTo(new SmartlistDsl.ContainsCondition("genre", "house"));
    }
}

package com.sashkomusic.agents.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryCommandParserTest {

    private final LibraryCommandParser parser = new LibraryCommandParser();

    @ParameterizedTest
    @CsvSource({
            "rate 5,         5",
            "оціни 4,        4",
            "оцени 3,        3",
            "постав 2,       2",
            "RATE 1,         1",
            "5 stars,        5",
            "3 зір,          3"
    })
    void parsesRate(String input, int expected) {
        var cmd = parser.parse(input);
        assertThat(cmd).isInstanceOfSatisfying(LibraryCommand.Rate.class,
                r -> assertThat(r.stars()).isEqualTo(expected));
    }

    @ParameterizedTest
    @CsvSource({
            "energy 3,    E3",
            "енергія 1,   E1",
            "e4,          E4",
            "E2,          E2"
    })
    void parsesEnergy(String input, String expected) {
        var cmd = parser.parse(input);
        assertThat(cmd).isInstanceOfSatisfying(LibraryCommand.SetEnergy.class,
                e -> assertThat(e.level()).isEqualTo(expected));
    }

    @ParameterizedTest
    @CsvSource({
            "марк банжер,        banger",
            "марк інтро,         intro",
            "познач тул,         tool",
            "марк клозер,        closer",
            "function banger,    banger",
            "функція closer,     closer"
    })
    void parsesFunction(String input, String expected) {
        var cmd = parser.parse(input);
        assertThat(cmd).isInstanceOfSatisfying(LibraryCommand.SetFunction.class,
                f -> assertThat(f.function()).isEqualTo(expected));
    }

    @Test
    void parsesComment() {
        var cmd = parser.parse("коментар крутий бенгер");
        assertThat(cmd).isInstanceOfSatisfying(LibraryCommand.AddComment.class,
                c -> assertThat(c.text()).isEqualTo("крутий бенгер"));
    }

    @Test
    void parsesCommentEnglish() {
        var cmd = parser.parse("comment topic for set");
        assertThat(cmd).isInstanceOfSatisfying(LibraryCommand.AddComment.class,
                c -> assertThat(c.text()).isEqualTo("topic for set"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"якась лажа", "12345", "хвороба ваша"})
    void parsesUnknown(String input) {
        var cmd = parser.parse(input);
        assertThat(cmd).isInstanceOf(LibraryCommand.Unknown.class);
    }

    @Test
    void emptyOrNullReturnsUnknown() {
        assertThat(parser.parse(null)).isInstanceOf(LibraryCommand.Unknown.class);
        assertThat(parser.parse("")).isInstanceOf(LibraryCommand.Unknown.class);
        assertThat(parser.parse("   ")).isInstanceOf(LibraryCommand.Unknown.class);
    }
}

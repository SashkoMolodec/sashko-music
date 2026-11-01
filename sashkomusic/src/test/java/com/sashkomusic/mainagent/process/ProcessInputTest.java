package com.sashkomusic.mainagent.process;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessInputTest {

    private static final String ARNIKA = "/Volumes/server_downloads/own_copy/arnika";

    private final Predicate<String> existing = Set.of(ARNIKA, "/Volumes/x/own copy/arnika")::contains;

    @Test
    void textAfterAPathIsTheContext() {
        ProcessInput input = ProcessInput.split(ARNIKA + " Арніка – Естрадний Ансамбль Арніка", existing);

        assertThat(input.folder()).isEqualTo(ARNIKA);
        assertThat(input.context()).isEqualTo("Арніка – Естрадний Ансамбль Арніка");
    }

    @Test
    void aBarePathHasNoContext() {
        ProcessInput input = ProcessInput.split(ARNIKA, existing);

        assertThat(input.folder()).isEqualTo(ARNIKA);
        assertThat(input.context()).isEmpty();
    }

    @Test
    void aFolderWithSpacesInItsNameStaysWhole() {
        ProcessInput input = ProcessInput.split("/Volumes/x/own copy/arnika касета 1990", existing);

        assertThat(input.folder()).isEqualTo("/Volumes/x/own copy/arnika");
        assertThat(input.context()).isEqualTo("касета 1990");
    }

    @Test
    void thePathNamingNoFolderIsLeftWhole() {
        ProcessInput input = ProcessInput.split("/Volumes/nowhere/album with words", existing);

        assertThat(input.folder()).isEqualTo("/Volumes/nowhere/album with words");
        assertThat(input.context()).isEmpty();
    }

    @Test
    void freeTextIsNeverSplit() {
        ProcessInput input = ProcessInput.split("Artist - Album касета 1990", existing);

        assertThat(input.folder()).isEqualTo("Artist - Album касета 1990");
        assertThat(input.context()).isEmpty();
    }
}

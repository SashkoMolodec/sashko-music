package com.sashkomusic.libraryagent.domain.service.processFolder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FolderAudioScannerTest {

    private final FolderAudioScanner scanner = new FolderAudioScanner();

    @TempDir
    Path dir;

    @Test
    void dropsSlskdRetryCopiesWhenOriginalExists() throws Exception {
        touch("01 - Intro.flac", "01 - Intro_639290487285837845.flac", "02 - Track.flac");

        List<String> result = scanner.listAudioFiles(dir);

        assertThat(result).extracting(p -> Path.of(p).getFileName().toString())
                .containsExactlyInAnyOrder("01 - Intro.flac", "02 - Track.flac");
    }

    @Test
    void keepsSuffixedFileWhenNoOriginal() throws Exception {
        touch("15 - Red Hills_639290488212384911.flac");

        assertThat(scanner.listAudioFiles(dir)).hasSize(1);
    }

    private void touch(String... names) throws Exception {
        for (String n : names) Files.createFile(dir.resolve(n));
    }
}

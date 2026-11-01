package com.sashkomusic.libraryagent.domain.service.processFolder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Test
    void aPastedPathWithAnUnknownMountResolvesToItsLastFolder() throws Exception {
        Path arnika = downloads("own_copy/arnika");
        downloads("kolory");

        assertThat(scanner.findClosestFolder("/Volumes/server_downloads/own_copy/arnika"))
                .contains(arnika);
    }

    @Test
    void theLastFolderOutscoresItsParentsInTheFuzzyMatch() throws Exception {
        Path arnika = downloads("own_copy/arnika");

        // "own_copy" shares two words with the whole path and "arnika" only one, but the typed name is arnik
        assertThat(scanner.findClosestFolder("/Volumes/x/own_copy/arnik")).contains(arnika);
    }

    @Test
    void aPlainFolderNameStillMatchesFuzzily() throws Exception {
        Path utopia = downloads("respublika biloho svitla - utopia no 233");

        assertThat(scanner.findClosestFolder("utopia")).contains(utopia);
    }

    @Test
    void aPathCannotLeaveTheDownloadsRoot() throws Exception {
        Files.createDirectory(dir.resolve("secret"));
        downloads("arnika");

        assertThat(scanner.findClosestFolder("/Volumes/../../secret")).isEmpty();
    }

    @Test
    void findExistingFolderNeverGuesses() throws Exception {
        Path arnika = downloads("own_copy/arnika");

        assertThat(scanner.findExistingFolder("/Volumes/any/own_copy/arnika")).contains(arnika);
        assertThat(scanner.findExistingFolder(arnika.toString())).contains(arnika);
        assertThat(scanner.findExistingFolder("/Volumes/any/own_copy/arnik")).isEmpty();
        assertThat(scanner.findExistingFolder("arnika")).isEmpty();
    }

    private Path downloads(String relative) throws Exception {
        Path base = dir.resolve("downloads");
        ReflectionTestUtils.setField(scanner, "downloadsBasePath", base.toString());
        return Files.createDirectories(base.resolve(relative));
    }

    private void touch(String... names) throws Exception {
        for (String n : names) Files.createFile(dir.resolve(n));
    }
}

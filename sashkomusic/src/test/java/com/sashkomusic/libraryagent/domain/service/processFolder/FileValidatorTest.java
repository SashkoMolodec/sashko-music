package com.sashkomusic.libraryagent.domain.service.processFolder;

import com.sashkomusic.libraryagent.domain.model.ValidationResult;
import com.sashkomusic.shared.task.ProcessLibraryTask;
import com.sashkomusic.shared.model.SearchEngine;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.TrackMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileValidatorTest {

    private final FileValidator validator = new FileValidator();

    private static ReleaseMetadata metadataWithTracks(int trackCount) {
        List<TrackMetadata> tracks = new ArrayList<>();
        for (int i = 1; i <= trackCount; i++) {
            tracks.add(new TrackMetadata(i, "Artist", "Track " + i));
        }
        return new ReleaseMetadata("discogs:1", null, SearchEngine.DISCOGS, "Artist", "Title", 100,
                List.of(), List.of(), trackCount, trackCount, 1, tracks, null, List.of(), "");
    }

    private static ReleaseMetadata metadataWithoutTrackList(int minTracks) {
        return new ReleaseMetadata("discogs:1", null, SearchEngine.DISCOGS, "Artist", "Title", 100,
                List.of(), List.of(), minTracks, minTracks, 1, List.of(), null, List.of(), "");
    }

    private List<String> createAudioFiles(Path dir, int count) throws IOException {
        List<String> files = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Path file = dir.resolve("track-" + i + ".mp3");
            Files.writeString(file, "fake audio bytes");
            files.add(file.toString());
        }
        return files;
    }

    @ParameterizedTest(name = "{0} audio files vs {1} expected tracks -> valid={2}")
    @CsvSource({
            // fileCount, trackCount, expectedValid
            "3, 3, true",   // exact match
            "4, 3, false",  // MORE files than tracks -> the real incident: duplicate track in another format
            "2, 3, true",   // FEWER files than tracks -> legitimate partial download (user picked a subset)
            "1, 3, true",   // grabbing a single off a maxi-single is a deliberate, supported flow
    })
    void validate_checksAudioFileCountAgainstExpectedTrackCount(int fileCount, int trackCount, boolean expectedValid,
                                                                  @TempDir Path tempDir) throws IOException {
        List<String> files = createAudioFiles(tempDir, fileCount);
        var task = new ProcessLibraryTask("1:conv", tempDir.toString(), files, metadataWithTracks(trackCount));

        ValidationResult result = validator.validate(task);

        assertThat(result.isValid()).isEqualTo(expectedValid);
        if (!expectedValid) {
            assertThat(result.getErrorMessage()).contains("exceeds expected track count");
        }
    }

    @Test
    void validate_exceedsCount_usesMinTracksFallback_whenNoTrackListProvided(@TempDir Path tempDir) throws IOException {
        List<String> files = createAudioFiles(tempDir, 4);
        var task = new ProcessLibraryTask("1:conv", tempDir.toString(), files, metadataWithoutTrackList(3));

        ValidationResult result = validator.validate(task);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessage()).contains("exceeds expected track count");
    }

    @Test
    void validate_passes_whenNoExpectedTrackCountIsKnown(@TempDir Path tempDir) throws IOException {
        List<String> files = createAudioFiles(tempDir, 5);
        var task = new ProcessLibraryTask("1:conv", tempDir.toString(), files, metadataWithoutTrackList(0));

        ValidationResult result = validator.validate(task);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void validate_fails_whenNoAudioFilesFound(@TempDir Path tempDir) {
        var task = new ProcessLibraryTask("1:conv", tempDir.toString(), List.of(), metadataWithTracks(3));

        ValidationResult result = validator.validate(task);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessage()).contains("No files provided for processing");
    }

    @Test
    void validate_fails_whenMetadataMissing(@TempDir Path tempDir) throws IOException {
        List<String> files = createAudioFiles(tempDir, 3);
        var task = new ProcessLibraryTask("1:conv", tempDir.toString(), files, null);

        ValidationResult result = validator.validate(task);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessage()).contains("No release metadata provided");
    }

    @Test
    void validate_fails_whenDirectoryDoesNotExist() {
        var task = new ProcessLibraryTask("1:conv", "/nonexistent/path/xyz", List.of("a.mp3"), metadataWithTracks(3));

        ValidationResult result = validator.validate(task);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessage()).contains("Directory does not exist");
    }
}

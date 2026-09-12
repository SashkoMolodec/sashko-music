package com.sashkomusic.downloadagent.domain.model;

import com.sashkomusic.shared.download.DownloadEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadBatchTest {

    private static DownloadBatch batchOf(String... files) {
        return new DownloadBatch("-100:7", "rel-1", "music\\album", List.of(files), DownloadEngine.SOULSEEK);
    }

    @Test
    void is_incomplete_until_every_file_is_marked() {
        var batch = batchOf("a.flac", "b.flac");

        assertThat(batch.isComplete()).isFalse();
        assertThat(batch.getRemainingCount()).isEqualTo(2);

        batch.markFileCompleted("a.flac");
        assertThat(batch.isComplete()).isFalse();
        assertThat(batch.getRemainingCount()).isEqualTo(1);

        batch.markFileCompleted("b.flac");
        assertThat(batch.isComplete()).isTrue();
        assertThat(batch.getRemainingCount()).isZero();
    }

    @Test
    void marking_the_same_file_twice_does_not_complete_the_batch_early() {
        var batch = batchOf("a.flac", "b.flac");

        batch.markFileCompleted("a.flac");
        batch.markFileCompleted("a.flac");

        assertThat(batch.isComplete()).isFalse();
        assertThat(batch.getRemainingCount()).isEqualTo(1);
    }

    @Test
    void marking_an_unknown_file_is_ignored() {
        var batch = batchOf("a.flac");

        batch.markFileCompleted("not-in-batch.flac");

        assertThat(batch.isComplete()).isFalse();
        assertThat(batch.getRemainingCount()).isEqualTo(1);
    }

    @Test
    void an_empty_batch_is_complete_immediately() {
        assertThat(batchOf().isComplete()).isTrue();
        assertThat(batchOf().getTotalFiles()).isZero();
    }

    @Test
    void local_directory_is_derived_from_the_first_local_file() {
        var batch = batchOf("a.flac");
        batch.addLocalFilename("/lib/Burial/Untrue/a.flac");

        assertThat(batch.getLocalDirectoryPath()).isEqualTo("/lib/Burial/Untrue");
    }

    @Test
    void local_directory_handles_windows_separators_from_soulseek() {
        var batch = batchOf("a.flac");
        batch.addLocalFilename("C:\\music\\Burial\\a.flac");

        assertThat(batch.getLocalDirectoryPath()).isEqualTo("C:\\music\\Burial");
    }

    @Test
    void local_directory_is_blank_before_any_file_lands() {
        assertThat(batchOf("a.flac").getLocalDirectoryPath()).isEmpty();
    }
}

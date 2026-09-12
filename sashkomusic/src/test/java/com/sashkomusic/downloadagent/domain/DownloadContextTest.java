package com.sashkomusic.downloadagent.domain;

import com.sashkomusic.downloadagent.config.SlskdPathConfig;
import com.sashkomusic.shared.download.DownloadEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadContextTest {

    private DownloadContext context;

    @BeforeEach
    void setUp() {
        SlskdPathConfig pathConfig = new SlskdPathConfig();
        pathConfig.setContainerPath("/downloads");
        pathConfig.setLocalPath("/local");
        context = new DownloadContext(pathConfig);
    }

    @Test
    void registered_batch_is_found_by_release_id() {
        context.registerBatch("-100:7", "rel-1", List.of("music\\album\\a.flac"), DownloadEngine.SOULSEEK);

        var batch = context.findBatchByReleaseId("rel-1");

        assertThat(batch).isNotNull();
        assertThat(batch.getRemoteDirectoryPath()).isEqualTo("music\\album");
        assertThat(batch.getTotalFiles()).isEqualTo(1);
    }

    @Test
    void completing_a_file_transforms_the_local_path_through_the_slskd_mapping() {
        context.registerBatch("-100:7", "rel-1", List.of("music\\a.flac", "music\\b.flac"), DownloadEngine.SOULSEEK);

        var batch = context.markFileCompleted("music\\a.flac", "/downloads/music/a.flac");

        assertThat(batch).isNotNull();
        assertThat(batch.getLocalFilenames()).containsExactly("/local/music/a.flac");
        assertThat(batch.isComplete()).isFalse();
    }

    @Test
    void batch_is_dropped_from_the_registry_once_the_last_file_completes() {
        context.registerBatch("-100:7", "rel-1", List.of("music\\a.flac"), DownloadEngine.SOULSEEK);

        var batch = context.markFileCompleted("music\\a.flac", "/downloads/music/a.flac");

        assertThat(batch.isComplete()).isTrue();
        assertThat(context.findBatchByReleaseId("rel-1")).isNull();
    }

    @Test
    void a_webhook_for_an_unknown_file_yields_no_batch() {
        context.registerBatch("-100:7", "rel-1", List.of("music\\a.flac"), DownloadEngine.SOULSEEK);

        assertThat(context.markFileCompleted("other\\z.flac", "/downloads/other/z.flac")).isNull();
    }

    @Test
    void removing_a_batch_reports_whether_it_existed() {
        context.registerBatch("-100:7", "rel-1", List.of("music\\a.flac"), DownloadEngine.SOULSEEK);

        assertThat(context.removeBatchByReleaseId("rel-1")).isTrue();
        assertThat(context.removeBatchByReleaseId("rel-1")).isFalse();
    }

    @Test
    void a_batch_with_no_files_has_no_remote_directory() {
        context.registerBatch("-100:7", "rel-1", List.of(), DownloadEngine.QOBUZ);

        assertThat(context.findBatchByReleaseId("rel-1").getRemoteDirectoryPath()).isEmpty();
    }
}

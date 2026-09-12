package com.sashkomusic.downloadagent.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlskdPathConfigTest {

    private static SlskdPathConfig config(String containerPath, String localPath) {
        SlskdPathConfig c = new SlskdPathConfig();
        c.setContainerPath(containerPath);
        c.setLocalPath(localPath);
        return c;
    }

    @Test
    void rewrites_container_prefix_to_local_prefix() {
        var c = config("/downloads", "/opt/sashko-music/downloads/slskd");

        assertThat(c.transformToLocalPath("/downloads/Burial/Untrue/01.flac"))
                .isEqualTo("/opt/sashko-music/downloads/slskd/Burial/Untrue/01.flac");
    }

    /** Guards against a plain String.replace, which would rewrite the repeated segment too. */
    @Test
    void rewrites_only_the_prefix_when_the_segment_repeats_deeper_in_the_path() {
        var c = config("/downloads", "/local");

        assertThat(c.transformToLocalPath("/downloads/artist/downloads/01.flac"))
                .isEqualTo("/local/artist/downloads/01.flac");
    }

    @Test
    void leaves_paths_that_do_not_start_with_the_container_prefix_untouched() {
        var c = config("/downloads", "/local");

        assertThat(c.transformToLocalPath("/elsewhere/01.flac")).isEqualTo("/elsewhere/01.flac");
    }

    @Test
    void passes_through_when_unconfigured_or_null() {
        assertThat(config(null, "/local").transformToLocalPath("/downloads/01.flac"))
                .isEqualTo("/downloads/01.flac");
        assertThat(config("/downloads", null).transformToLocalPath("/downloads/01.flac"))
                .isEqualTo("/downloads/01.flac");
        assertThat(config("/downloads", "/local").transformToLocalPath(null)).isNull();
    }
}

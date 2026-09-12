package com.sashkomusic.downloadagent.domain;

import com.sashkomusic.downloadagent.domain.DownloadMonitorService.DownloadMonitorTask;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the stall/stability state machine that decides when a download is finished or dead.
 * The elapsed-time branches (2 min / 5 min / 6 h) are not covered — the task reads
 * {@code Instant.now()} directly, so they would need an injected Clock to test.
 */
class DownloadMonitorTaskTest {

    private static DownloadMonitorTask task() {
        return new DownloadMonitorTask("-100:7", "rel-1", "/downloads", 5, "Burial", "Untrue");
    }

    @Test
    void needs_two_consecutive_equal_counts_to_call_a_download_stable() {
        var t = task();

        assertThat(t.isStable(3)).isFalse();  // first sighting of 3
        assertThat(t.isStable(3)).isFalse();  // one stable check
        assertThat(t.isStable(3)).isTrue();   // two stable checks
    }

    @Test
    void a_changing_file_count_restarts_the_stability_streak() {
        var t = task();

        t.isStable(3);
        t.isStable(3);
        assertThat(t.isStable(4)).isFalse();  // grew — streak reset
        assertThat(t.isStable(4)).isFalse();
        assertThat(t.isStable(4)).isTrue();
    }

    @Test
    void an_empty_folder_never_counts_as_stable() {
        var t = task();

        assertThat(t.isStable(0)).isFalse();
        assertThat(t.isStable(0)).isFalse();
        assertThat(t.isStable(0)).isFalse();
    }

    @Test
    void temp_files_reset_the_streak_so_a_paused_transfer_is_not_mistaken_for_done() {
        var t = task();

        t.isStable(3);
        t.isStable(3);
        t.resetStableChecks();

        assertThat(t.isStable(3)).isFalse();
    }

    @Test
    void a_download_with_no_files_yet_is_not_reported_as_stalled() {
        assertThat(task().isStalled(0)).isFalse();
    }

    @Test
    void a_fresh_task_has_not_timed_out() {
        var t = task();

        assertThat(t.isTimedOut()).isFalse();
        assertThat(t.isFolderCreationTimedOut()).isFalse();
        assertThat(t.isStalled(2)).isFalse();
    }

    @Test
    void exposes_the_metadata_the_monitor_loop_reads() {
        var t = task();

        assertThat(t.conversationId()).isEqualTo("-100:7");
        assertThat(t.releaseId()).isEqualTo("rel-1");
        assertThat(t.downloadPath()).isEqualTo("/downloads");
        assertThat(t.expectedFileCount()).isEqualTo(5);
        assertThat(t.artist()).isEqualTo("Burial");
        assertThat(t.title()).isEqualTo("Untrue");
    }
}

package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.AppleMusicSyncFinishedEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppleMusicSyncFinishedListenerTest {

    @Test
    void full_success_says_added_to_itunes_not_in_apple_music() {
        String text = AppleMusicSyncFinishedListener.format(new AppleMusicSyncFinishedEvent("1", "A — B", 5, 5, null));
        assertThat(text).startsWith("🍎").contains("A — B", "5/5", "iTunes");
    }

    @Test
    void partial_add_is_a_warning() {
        String text = AppleMusicSyncFinishedListener.format(new AppleMusicSyncFinishedEvent("1", "A — B", 5, 3, null));
        assertThat(text).startsWith("⚠️").contains("3/5");
    }

    @Test
    void error_keeps_partial_counts_and_reason() {
        String text = AppleMusicSyncFinishedListener.format(new AppleMusicSyncFinishedEvent("1", "A — B", 5, 2, "таймаут 600с"));
        assertThat(text).startsWith("❌").contains("2/5", "таймаут 600с");
    }

    @Test
    void error_before_staging_has_no_counts() {
        String text = AppleMusicSyncFinishedListener.format(new AppleMusicSyncFinishedEvent("1", "A — B", 0, 0, "boom"));
        assertThat(text).doesNotContain("0/0").contains("boom");
    }
}

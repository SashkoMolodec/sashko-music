package com.sashkomusic.events;

import com.sashkomusic.shared.ConversationScoped;

/**
 * One Apple Music sync run is over. {@code error} is null when the script ran to completion;
 * {@code added < staged} then means some tracks were rejected by iTunes.
 */
public record AppleMusicSyncFinishedEvent(
        String conversationId,
        String label,
        int staged,
        int added,
        String error
) implements ConversationScoped {

    public boolean success() {
        return error == null && staged > 0 && added == staged;
    }
}

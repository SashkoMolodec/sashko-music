package com.sashkomusic.events;

import com.sashkomusic.shared.ConversationScoped;
import com.sashkomusic.shared.task.ReprocessOptions;
import com.sashkomusic.shared.model.ReleaseMetadata;

public record ReprocessReleaseTaskEvent(
        String conversationId,
        String directoryPath,
        ReleaseMetadata metadata,
        int newMetadataVersion,
        ReprocessOptions options
) implements ConversationScoped {}

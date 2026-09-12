package com.sashkomusic.events;

import com.sashkomusic.shared.ConversationScoped;
public record ReprocessReleaseCompleteEvent(
        String conversationId,
        String directoryPath,
        boolean success,
        String message,
        int filesProcessed,
        int errors
) implements ConversationScoped {}

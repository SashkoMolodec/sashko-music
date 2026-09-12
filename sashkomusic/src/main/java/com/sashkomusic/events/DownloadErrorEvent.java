package com.sashkomusic.events;

import com.sashkomusic.shared.ConversationScoped;
public record DownloadErrorEvent(
        String conversationId,
        String errorMessage
) implements ConversationScoped {}

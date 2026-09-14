package com.sashkomusic.events;

import com.sashkomusic.shared.ConversationScoped;

public record AddMarkerTaskEvent(
        Long trackId,
        String marker,
        String conversationId
) implements ConversationScoped {}

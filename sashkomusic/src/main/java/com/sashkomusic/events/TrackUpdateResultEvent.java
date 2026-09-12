package com.sashkomusic.events;

import com.sashkomusic.shared.ConversationScoped;
public record TrackUpdateResultEvent(
        Long trackId,
        String fieldUpdated,
        String value,
        boolean success,
        String message,
        String conversationId
) implements ConversationScoped {}

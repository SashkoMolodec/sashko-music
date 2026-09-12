package com.sashkomusic.events;

import com.sashkomusic.shared.ConversationScoped;
public record RateTrackTaskEvent(
        Long trackId,
        int rating,
        String conversationId
) implements ConversationScoped {}

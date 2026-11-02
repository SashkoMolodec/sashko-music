package com.sashkomusic.events;

import com.sashkomusic.shared.ConversationScoped;

/** Drop a marker from a track's comment. {@code markerName} has no parentheses: "detroit techno". */
public record RemoveMarkerTaskEvent(
        Long trackId,
        String markerName,
        String conversationId
) implements ConversationScoped {}

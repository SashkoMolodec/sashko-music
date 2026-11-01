package com.sashkomusic.events;

import com.sashkomusic.shared.ConversationScoped;

/** A freshly processed release got its first sub-library choice, so its path is final. Published once per release. */
public record ReleasePlacedEvent(
        String conversationId,
        Long releaseId,
        String releaseTitle,
        String directoryPath
) implements ConversationScoped {}

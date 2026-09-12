package com.sashkomusic.events;

import com.sashkomusic.shared.ConversationScoped;
/** energy is E1-E5. */
public record SetEnergyTaskEvent(
        Long trackId,
        String energy,
        String conversationId
) implements ConversationScoped {}

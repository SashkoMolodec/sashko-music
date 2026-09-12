package com.sashkomusic.events;

import com.sashkomusic.shared.ConversationScoped;
/** function is intro|tool|banger|closer. */
public record SetFunctionTaskEvent(
        Long trackId,
        String function,
        String conversationId
) implements ConversationScoped {}

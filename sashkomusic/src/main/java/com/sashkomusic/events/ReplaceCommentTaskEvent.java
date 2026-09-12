package com.sashkomusic.events;

import com.sashkomusic.shared.ConversationScoped;
public record ReplaceCommentTaskEvent(
        Long trackId,
        String comment,
        String conversationId
) implements ConversationScoped {}

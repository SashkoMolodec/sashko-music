package com.sashkomusic.events;

import com.sashkomusic.shared.ConversationScoped;
public record AddCommentTaskEvent(
        Long trackId,
        String comment,
        String conversationId
) implements ConversationScoped {}

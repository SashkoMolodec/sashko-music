package com.sashkomusic.mainagent.library.messaging.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("replace_comment_task")
public record ReplaceCommentTaskDto(
        Long trackId,
        String comment,
        String conversationId
) {
    public long chatId() {
        int colon = conversationId.indexOf(':');
        return Long.parseLong(colon < 0 ? conversationId : conversationId.substring(0, colon));
    }
}

package com.sashkomusic.mainagent.library.messaging.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("set_function_task")
public record SetFunctionTaskDto(
        Long trackId,
        String function,  // intro|tool|banger|closer
        String conversationId
) {
    public long chatId() {
        int colon = conversationId.indexOf(':');
        return Long.parseLong(colon < 0 ? conversationId : conversationId.substring(0, colon));
    }
}

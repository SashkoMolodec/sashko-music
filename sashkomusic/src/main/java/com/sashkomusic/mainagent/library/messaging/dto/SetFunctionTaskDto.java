package com.sashkomusic.mainagent.library.messaging.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("set_function_task")
public record SetFunctionTaskDto(
        Long trackId,
        String function,  // intro|tool|banger|closer
        long chatId
) {
}

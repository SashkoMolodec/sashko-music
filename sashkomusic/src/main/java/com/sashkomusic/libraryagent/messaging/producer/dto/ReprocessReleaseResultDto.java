package com.sashkomusic.libraryagent.messaging.producer.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("reprocess_complete")
public record ReprocessReleaseResultDto(
        String conversationId,
        String directoryPath,
        boolean success,
        String message,
        int filesProcessed,
        int errors
) {
    public long chatId() {
        int colon = conversationId.indexOf(':');
        return Long.parseLong(colon < 0 ? conversationId : conversationId.substring(0, colon));
    }
}

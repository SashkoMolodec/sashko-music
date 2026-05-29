package com.sashkomusic.libraryagent.messaging.producer.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("reprocess_complete")
public record ReprocessReleaseResultDto(
        long chatId,
        String directoryPath,
        boolean success,
        String message,
        int filesProcessed,
        int errors
) {
}

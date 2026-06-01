package com.sashkomusic.libraryagent.messaging.producer.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.sashkomusic.libraryagent.domain.model.ProcessedFile;

import java.util.List;

@JsonTypeName("library_complete")
public record LibraryProcessingCompleteDto(
        String conversationId,
        String masterId,
        String directoryPath,
        List<ProcessedFile> processedFiles,
        boolean success,
        String message,
        List<String> errors
) {
    public long chatId() {
        int colon = conversationId.indexOf(':');
        return Long.parseLong(colon < 0 ? conversationId : conversationId.substring(0, colon));
    }
}

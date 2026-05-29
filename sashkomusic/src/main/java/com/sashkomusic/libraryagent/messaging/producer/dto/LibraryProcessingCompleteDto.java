package com.sashkomusic.libraryagent.messaging.producer.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.sashkomusic.libraryagent.domain.model.ProcessedFile;

import java.util.List;

@JsonTypeName("library_complete")
public record LibraryProcessingCompleteDto(
        long chatId,
        String masterId,
        String directoryPath,
        List<ProcessedFile> processedFiles,
        boolean success,
        String message,
        List<String> errors
) {
}
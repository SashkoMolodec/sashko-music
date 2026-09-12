package com.sashkomusic.events;

import com.sashkomusic.shared.ConversationScoped;
import com.sashkomusic.libraryagent.domain.model.ProcessedFile;

import java.util.List;

public record LibraryProcessingCompleteEvent(
        String conversationId,
        String masterId,
        String directoryPath,
        List<ProcessedFile> processedFiles,
        boolean success,
        String message,
        List<String> errors
) implements ConversationScoped {}

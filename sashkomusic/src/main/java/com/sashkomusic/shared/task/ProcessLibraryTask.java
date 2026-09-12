package com.sashkomusic.shared.task;

import com.sashkomusic.shared.ConversationScoped;
import com.sashkomusic.shared.model.ReleaseMetadata;

import java.util.List;

public record ProcessLibraryTask(
        String conversationId,
        String directoryPath,
        List<String> downloadedFiles,
        ReleaseMetadata metadata
) implements ConversationScoped {}

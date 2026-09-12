package com.sashkomusic.shared.task;

import com.sashkomusic.shared.ConversationScoped;
import com.sashkomusic.shared.download.DownloadEngine;

public record SearchFilesTask(
        String conversationId,
        String releaseId,
        String artist,
        String title,
        DownloadEngine source
) implements ConversationScoped {}

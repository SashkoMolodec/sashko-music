package com.sashkomusic.shared.task;

import com.sashkomusic.shared.ConversationScoped;
import com.sashkomusic.shared.download.DownloadOption;

public record DownloadFilesTask(
        String conversationId,
        String releaseId,
        DownloadOption downloadOption
) implements ConversationScoped {}

package com.sashkomusic.events;

import com.sashkomusic.shared.ConversationScoped;
import java.util.List;

public record DownloadBatchCompleteEvent(
        String conversationId,
        String releaseId,
        String directoryPath,
        List<String> allFiles
) implements ConversationScoped {

    public int totalFiles() {
        return allFiles.size();
    }
}

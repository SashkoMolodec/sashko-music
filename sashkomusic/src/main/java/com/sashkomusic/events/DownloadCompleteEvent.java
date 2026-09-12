package com.sashkomusic.events;

import com.sashkomusic.shared.ConversationScoped;
public record DownloadCompleteEvent(
        String conversationId,
        String filename,
        long sizeMB
) implements ConversationScoped {

    public static DownloadCompleteEvent of(String conversationId, String filename, long sizeBytes) {
        return new DownloadCompleteEvent(conversationId, filename, sizeBytes / (1024 * 1024));
    }
}

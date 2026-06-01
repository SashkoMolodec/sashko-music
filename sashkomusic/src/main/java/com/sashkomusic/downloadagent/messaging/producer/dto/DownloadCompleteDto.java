package com.sashkomusic.downloadagent.messaging.producer.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("download_complete")
public record DownloadCompleteDto(
        String conversationId,
        String filename,
        long sizeMB
) {
    public long chatId() {
        int colon = conversationId.indexOf(':');
        return Long.parseLong(colon < 0 ? conversationId : conversationId.substring(0, colon));
    }

    public static DownloadCompleteDto of(String conversationId, String filename, long sizeBytes) {
        return new DownloadCompleteDto(conversationId, filename, sizeBytes / (1024 * 1024));
    }
}

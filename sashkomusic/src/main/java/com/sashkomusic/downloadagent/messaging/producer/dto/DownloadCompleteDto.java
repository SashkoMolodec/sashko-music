package com.sashkomusic.downloadagent.messaging.producer.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("download_complete")
public record DownloadCompleteDto(
        long chatId,
        String filename,
        long sizeMB
) {
    public static DownloadCompleteDto of(long chatId, String filename, long sizeBytes) {
        return new DownloadCompleteDto(chatId, filename, sizeBytes / (1024 * 1024));
    }
}
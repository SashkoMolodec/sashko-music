package com.sashkomusic.mainagent.download.messaging.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("download_cancel")
public record DownloadCancelTaskDto(
        long chatId,
        String releaseId
) {
    public static DownloadCancelTaskDto of(long chatId, String releaseId) {
        return new DownloadCancelTaskDto(chatId, releaseId);
    }
}

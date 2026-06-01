package com.sashkomusic.mainagent.download.messaging.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("download_cancel")
public record DownloadCancelTaskDto(
        String conversationId,
        String releaseId
) {
    public long chatId() {
        int colon = conversationId.indexOf(':');
        return Long.parseLong(colon < 0 ? conversationId : conversationId.substring(0, colon));
    }

    public static DownloadCancelTaskDto of(String conversationId, String releaseId) {
        return new DownloadCancelTaskDto(conversationId, releaseId);
    }
}

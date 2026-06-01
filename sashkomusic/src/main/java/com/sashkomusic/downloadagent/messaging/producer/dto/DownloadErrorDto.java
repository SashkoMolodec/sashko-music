package com.sashkomusic.downloadagent.messaging.producer.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("download_error")
public record DownloadErrorDto(
        String conversationId,
        String errorMessage
) {
    public long chatId() {
        int colon = conversationId.indexOf(':');
        return Long.parseLong(colon < 0 ? conversationId : conversationId.substring(0, colon));
    }

    public static DownloadErrorDto of(String conversationId, String errorMessage) {
        return new DownloadErrorDto(conversationId, errorMessage);
    }
}

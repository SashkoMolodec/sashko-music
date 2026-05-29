package com.sashkomusic.downloadagent.messaging.producer.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("download_error")
public record DownloadErrorDto(
        long chatId,
        String errorMessage
) {
    public static DownloadErrorDto of(long chatId, String errorMessage) {
        return new DownloadErrorDto(chatId, errorMessage);
    }
}
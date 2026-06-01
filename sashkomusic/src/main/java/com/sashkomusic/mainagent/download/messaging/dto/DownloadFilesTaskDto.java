package com.sashkomusic.mainagent.download.messaging.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.sashkomusic.mainagent.download.DownloadOption;

@JsonTypeName("download_request")
public record DownloadFilesTaskDto(
        String conversationId,
        String releaseId,
        DownloadOption downloadOption) {

    public long chatId() {
        int colon = conversationId.indexOf(':');
        return Long.parseLong(colon < 0 ? conversationId : conversationId.substring(0, colon));
    }

    public static DownloadFilesTaskDto of(String conversationId, String releaseId, DownloadOption downloadOption) {
        return new DownloadFilesTaskDto(conversationId, releaseId, downloadOption);
    }
}

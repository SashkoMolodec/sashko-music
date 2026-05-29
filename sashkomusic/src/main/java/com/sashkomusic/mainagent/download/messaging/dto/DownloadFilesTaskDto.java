package com.sashkomusic.mainagent.download.messaging.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.sashkomusic.mainagent.download.DownloadOption;

@JsonTypeName("download_request")
public record DownloadFilesTaskDto(
        long chatId,
        String releaseId,
        DownloadOption downloadOption) {

    public static DownloadFilesTaskDto of(long chatId, String releaseId, DownloadOption downloadOption) {
        return new DownloadFilesTaskDto(chatId, releaseId, downloadOption);
    }
}
